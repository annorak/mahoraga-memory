package dev.mahoraga.memory.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.InvalidSourceEventException;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.database.TestDatabase;
import dev.mahoraga.memory.ingest.SourceEventConflictException.Reason;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the ingestion contract against real PostgreSQL: exactly-once
 * acceptance, exact-retry no-op, distinct conflict reasons, bidirectional
 * stream binding, and one transaction shared by the inbox row and domain work.
 * Tests use disjoint tenants and streams inside one migrated database.
 */
class IngestionTransactionTest {

  private static final String PAYLOAD_DNS = "api.internal.demo";
  private static final String OCCURRED_AT = "2026-01-01T10:00:00Z";

  private static Jdbi jdbi;
  private static IngestionTransaction transaction;
  private static SourceEventCodec codec;

  @BeforeAll
  static void migrateAndConnect() throws SQLException {
    String url = TestDatabase.ensureDatabase("ingest_inbox");
    Flyway.configure()
        .dataSource(url, TestDatabase.username(), TestDatabase.password())
        .load()
        .migrate();
    jdbi = Jdbi.create(url, TestDatabase.username(), TestDatabase.password());
    transaction = new IngestionTransaction(jdbi, new SourceEventInbox(Jackson.newObjectMapper()));
    codec =
        new SourceEventCodec(
            Jackson.newObjectMapper(), new SourceEventValidator(BaseValidator.newValidator()));
  }

  @Test
  void firstValidEventIsAcceptedAndRunsWorkOnceInTheSameTransaction() {
    TrustedContext context = new TrustedContext("t-first", "eng-1");
    CanonicalSourceEvent canonical = event("evt-first-1", "stream-first", 1);
    AtomicInteger workCalls = new AtomicInteger();

    IngestResult result =
        transaction.ingest(
            context,
            canonical,
            handle -> {
              workCalls.incrementAndGet();
              // The inbox row must already be visible on this same handle.
              int visible =
                  handle
                      .createQuery(
                          "SELECT count(*) FROM source_events WHERE tenant_id = :tenantId"
                              + " AND source_event_id = :sourceEventId")
                      .bind("tenantId", "t-first")
                      .bind("sourceEventId", "evt-first-1")
                      .mapTo(Integer.class)
                      .one();
              assertEquals(1, visible, "work must run after the inbox insert, same transaction");
            });

    assertEquals(IngestResult.ACCEPTED, result);
    assertEquals(1, workCalls.get());
    assertEquals(1, engagementCount("t-first"));
    assertEquals(1, sourceEventCount("t-first"));
    assertEquals(canonical.canonicalSourceHash(), storedHash("t-first", "evt-first-1"));
    assertEquals(PAYLOAD_DNS, storedPayloadDns("t-first", "evt-first-1"));
  }

  @Test
  void exactRetryIsNoOpAndNeverRerunsWork() {
    TrustedContext context = new TrustedContext("t-retry", "eng-1");
    CanonicalSourceEvent canonical = event("evt-retry-1", "stream-retry", 1);
    transaction.ingest(context, canonical, handle -> {});

    IngestResult retry =
        transaction.ingest(
            context, canonical, handle -> fail("domain work must not run on an exact retry"));

    assertEquals(IngestResult.NO_OP, retry);
    assertEquals(1, sourceEventCount("t-retry"));
  }

  @Test
  void occurredAtChangeAloneIsAnEventContentConflict() {
    TrustedContext context = new TrustedContext("t-time", "eng-1");
    transaction.ingest(context, event("evt-time-1", "stream-time", 1), handle -> {});

    CanonicalSourceEvent changed =
        event("evt-time-1", "stream-time", 1, "2026-01-01T10:00:00.000001Z");
    SourceEventConflictException conflict =
        assertThrows(
            SourceEventConflictException.class,
            () -> transaction.ingest(context, changed, handle -> fail("work must not run")));

    assertEquals(Reason.EVENT_CONTENT, conflict.reason());
    assertSafeMessage(conflict, "evt-time-1");
    assertEquals(1, sourceEventCount("t-time"));
  }

  @Test
  void schemaVersionChangeIsRejectedByTheContractBeforeIngestion() {
    assertThrows(
        InvalidSourceEventException.class,
        () -> decode(eventJson("evt-schema-1", "stream-schema", 1, OCCURRED_AT, 2)));
  }

  @Test
  void occupiedPositionWithDifferentEventIdIsAPositionConflict() {
    TrustedContext context = new TrustedContext("t-pos", "eng-1");
    transaction.ingest(context, event("evt-pos-a", "stream-pos", 1), handle -> {});

    SourceEventConflictException conflict =
        assertThrows(
            SourceEventConflictException.class,
            () ->
                transaction.ingest(
                    context, event("evt-pos-b", "stream-pos", 1), handle -> fail("no work")));

    assertEquals(Reason.STREAM_POSITION, conflict.reason());
    assertSafeMessage(conflict, "evt-pos-b");
    assertEquals(1, sourceEventCount("t-pos"));
  }

  @Test
  void streamReuseByAnotherTenantIsAnOwnershipConflict() {
    transaction.ingest(
        new TrustedContext("t-owner-a", "eng-1"),
        event("evt-owner-1", "stream-owned", 1),
        handle -> {});

    SourceEventConflictException conflict =
        assertThrows(
            SourceEventConflictException.class,
            () ->
                transaction.ingest(
                    new TrustedContext("t-owner-b", "eng-1"),
                    event("evt-owner-2", "stream-owned", 2),
                    handle -> fail("no work")));

    assertEquals(Reason.STREAM_OWNERSHIP, conflict.reason());
    assertFalse(conflict.getMessage().contains("t-owner-a"), "must not reveal the actual owner");
    assertEquals(0, engagementCount("t-owner-b"));
  }

  @Test
  void streamReuseByAnotherEngagementIsAnOwnershipConflict() {
    transaction.ingest(
        new TrustedContext("t-eng", "eng-1"), event("evt-eng-1", "stream-eng", 1), handle -> {});

    SourceEventConflictException conflict =
        assertThrows(
            SourceEventConflictException.class,
            () ->
                transaction.ingest(
                    new TrustedContext("t-eng", "eng-2"),
                    event("evt-eng-2", "stream-eng", 2),
                    handle -> fail("no work")));

    assertEquals(Reason.STREAM_OWNERSHIP, conflict.reason());
  }

  @Test
  void engagementBoundToAnotherStreamIsABindingConflict() {
    TrustedContext context = new TrustedContext("t-bind", "eng-1");
    transaction.ingest(context, event("evt-bind-1", "stream-bind-1", 1), handle -> {});

    SourceEventConflictException conflict =
        assertThrows(
            SourceEventConflictException.class,
            () ->
                transaction.ingest(
                    context, event("evt-bind-2", "stream-bind-2", 1), handle -> fail("no work")));

    assertEquals(Reason.ENGAGEMENT_BINDING, conflict.reason());
    assertEquals(1, engagementCount("t-bind"));
  }

  @Test
  void sameEventIdUnderAnotherTenantWithItsOwnStreamIsIndependent() {
    transaction.ingest(
        new TrustedContext("t-share-a", "eng-1"),
        event("evt-shared", "stream-share-a", 1),
        handle -> {});
    IngestResult result =
        transaction.ingest(
            new TrustedContext("t-share-b", "eng-1"),
            event("evt-shared", "stream-share-b", 1),
            handle -> {});

    assertEquals(IngestResult.ACCEPTED, result);
    assertEquals(1, sourceEventCount("t-share-a"));
    assertEquals(1, sourceEventCount("t-share-b"));
  }

  @Test
  void workFailureRollsBackEverythingAndExplicitRetrySucceedsOnce() {
    TrustedContext context = new TrustedContext("t-roll", "eng-1");
    CanonicalSourceEvent canonical = event("evt-roll-1", "stream-roll", 1);
    AtomicInteger workCalls = new AtomicInteger();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                transaction.ingest(
                    context,
                    canonical,
                    handle -> {
                      insertAsset(handle, "t-roll");
                      throw new IllegalStateException("forced work failure");
                    }));
    assertEquals("forced work failure", failure.getMessage());
    assertEquals(0, engagementCount("t-roll"));
    assertEquals(0, sourceEventCount("t-roll"));
    assertEquals(0, assetCount("t-roll"));

    IngestResult retry =
        transaction.ingest(
            context,
            canonical,
            handle -> {
              workCalls.incrementAndGet();
              insertAsset(handle, "t-roll");
            });

    assertEquals(IngestResult.ACCEPTED, retry);
    assertEquals(1, workCalls.get());
    assertEquals(1, engagementCount("t-roll"));
    assertEquals(1, sourceEventCount("t-roll"));
    assertEquals(1, assetCount("t-roll"));
  }

  @Test
  void restartWithFreshConnectionsPreservesDuplicateAndConflictOutcomes() {
    TrustedContext context = new TrustedContext("t-restart", "eng-1");
    CanonicalSourceEvent canonical = event("evt-restart-1", "stream-restart", 1);
    transaction.ingest(context, canonical, handle -> {});

    // A new Jdbi and transaction instance carries no in-memory state; outcomes
    // must come from the durable hash and position alone.
    IngestionTransaction restarted =
        new IngestionTransaction(
            Jdbi.create(
                TestDatabase.urlFor("ingest_inbox"),
                TestDatabase.username(),
                TestDatabase.password()),
            new SourceEventInbox(Jackson.newObjectMapper()));

    assertEquals(
        IngestResult.NO_OP,
        restarted.ingest(context, canonical, handle -> fail("work must not run")));
    SourceEventConflictException conflict =
        assertThrows(
            SourceEventConflictException.class,
            () ->
                restarted.ingest(
                    context,
                    event("evt-restart-1", "stream-restart", 1, "2027-06-30T00:00:00Z"),
                    handle -> fail("no work")));
    assertEquals(Reason.EVENT_CONTENT, conflict.reason());
  }

  @Test
  void identifiersWithQuotesAreHandledByParameterizedSql() {
    TrustedContext context = new TrustedContext("t-quote'; --", "eng'1");
    CanonicalSourceEvent canonical = event("evt'quote-1", "stream'quote", 1);

    assertEquals(IngestResult.ACCEPTED, transaction.ingest(context, canonical, handle -> {}));
    assertEquals(IngestResult.NO_OP, transaction.ingest(context, canonical, handle -> fail("")));
    assertEquals(1, sourceEventCount("t-quote'; --"));
  }

  private static CanonicalSourceEvent event(String eventId, String streamId, long sequence) {
    return event(eventId, streamId, sequence, OCCURRED_AT);
  }

  private static CanonicalSourceEvent event(
      String eventId, String streamId, long sequence, String occurredAt) {
    return decode(eventJson(eventId, streamId, sequence, occurredAt, 1));
  }

  private static CanonicalSourceEvent decode(String json) {
    return codec.decode(json);
  }

  private static String eventJson(
      String eventId, String streamId, long sequence, String occurredAt, int schemaVersion) {
    return """
        {
          "source_event_id": "%s",
          "event_type": "asset_observation",
          "source_stream_id": "%s",
          "source_sequence": %d,
          "schema_version": %d,
          "occurred_at": "%s",
          "payload": {
            "cluster_id": "cluster-demo",
            "resource_kind": "Deployment",
            "resource_uid": "deploy-uid-1",
            "dns": "%s"
          }
        }
        """
        .formatted(eventId, streamId, sequence, schemaVersion, occurredAt, PAYLOAD_DNS);
  }

  /** Conflict messages carry stable identifiers but never payload content. */
  private static void assertSafeMessage(SourceEventConflictException conflict, String eventId) {
    assertTrue(conflict.getMessage().contains(eventId), "message should name the event");
    assertFalse(conflict.getMessage().contains(PAYLOAD_DNS), "message must not carry payload");
    assertFalse(conflict.getMessage().contains("cluster-demo"), "message must not carry payload");
  }

  private static void insertAsset(Handle handle, String tenantId) {
    handle
        .createUpdate(
            "INSERT INTO assets (tenant_id, canonical_asset_id, cluster_id, resource_kind,"
                + " resource_uid) VALUES (:tenantId, :assetId, 'cluster-demo', 'Deployment',"
                + " 'deploy-uid-1')")
        .bind("tenantId", tenantId)
        .bind("assetId", UUID.randomUUID())
        .execute();
  }

  private static int engagementCount(String tenantId) {
    return countWhereTenant("engagements", tenantId);
  }

  private static int sourceEventCount(String tenantId) {
    return countWhereTenant("source_events", tenantId);
  }

  private static int assetCount(String tenantId) {
    return countWhereTenant("assets", tenantId);
  }

  private static int countWhereTenant(String table, String tenantId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT count(*) FROM " + table + " WHERE tenant_id = :tenantId")
                .bind("tenantId", tenantId)
                .mapTo(Integer.class)
                .one());
  }

  private static String storedHash(String tenantId, String sourceEventId) {
    return selectEventColumn(tenantId, sourceEventId, "canonical_source_hash");
  }

  private static String storedPayloadDns(String tenantId, String sourceEventId) {
    return selectEventColumn(tenantId, sourceEventId, "payload->>'dns'");
  }

  private static String selectEventColumn(String tenantId, String sourceEventId, String column) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT "
                        + column
                        + " FROM source_events WHERE tenant_id = :tenantId"
                        + " AND source_event_id = :sourceEventId")
                .bind("tenantId", tenantId)
                .bind("sourceEventId", sourceEventId)
                .mapTo(String.class)
                .one());
  }
}
