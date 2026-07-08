package dev.mahoraga.memory.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.InvalidSourceEventException;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.contract.SourcePayload.FindingObservation;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.database.TestDatabase;
import dev.mahoraga.memory.identity.AssetIdentityService;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.ingest.IngestionFaultHook;
import dev.mahoraga.memory.ingest.IngestionTransaction;
import dev.mahoraga.memory.ingest.SourceEventInbox;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves finding match policy v1 through the PostgreSQL ingestion transaction
 * with asset resolution on the same handle: exact five-part identity,
 * immutable four-part baseline, one occurrence per detection, and atomic
 * rollback with the source event.
 */
class FindingIdentityServiceTest {

  private static final ObjectMapper MAPPER = Jackson.newObjectMapper();
  private static final String OCCURRED_AT = "2026-01-01T10:00:00Z";

  private static Jdbi jdbi;
  private static IngestionTransaction transaction;
  private static FindingIdentityService service;
  private static SourceEventCodec codec;

  @BeforeAll
  static void migrateAndConnect() throws SQLException {
    String url = TestDatabase.ensureDatabase("finding_identity");
    Flyway.configure()
        .dataSource(url, TestDatabase.username(), TestDatabase.password())
        .load()
        .migrate();
    jdbi = Jdbi.create(url, TestDatabase.username(), TestDatabase.password());
    transaction = new IngestionTransaction(jdbi, new SourceEventInbox());
    service =
        new FindingIdentityService(
            new AssetIdentityService(MAPPER, IngestionFaultHook.NO_FAULTS),
            IngestionFaultHook.NO_FAULTS);
    codec = new SourceEventCodec(MAPPER, new SourceEventValidator(BaseValidator.newValidator()));
  }

  @Test
  void firstObservationCreatesAssetFindingAndOccurrence() {
    TrustedContext context = new TrustedContext("t-first", "eng-1");
    FindingId findingId = ingest(context, "stream-first", 1, "evt-first-1", Map.of());

    assertEquals(1, count("assets", "t-first"));
    assertEquals(1, count("findings", "t-first"));
    assertEquals(1, count("finding_occurrences", "t-first"));
    Map<String, Object> row = findingRow("t-first", findingId);
    assertEquals("check-xss-1", row.get("verification_key"));
    assertEquals("1.0", row.get("check_version"));
    assertEquals(1, row.get("match_key_version"));
    assertEquals(1, row.get("compatibility_policy_version"));
  }

  @Test
  void sameIdentityInASecondEngagementReusesTheFindingWithoutInputIds() {
    TrustedContext first = new TrustedContext("t-cross", "eng-1");
    TrustedContext second = new TrustedContext("t-cross", "eng-2");
    FindingId original = ingest(first, "stream-cross-1", 1, "evt-cross-1", Map.of());
    FindingId reused = ingest(second, "stream-cross-2", 1, "evt-cross-2", Map.of());

    assertEquals(original, reused);
    assertEquals(1, count("findings", "t-cross"));
    assertEquals(2, count("finding_occurrences", "t-cross"));
  }

  @Test
  void changingOneMatchComponentAloneCreatesADistinctFinding() {
    TrustedContext context = new TrustedContext("t-comp", "eng-1");
    FindingId base = ingest(context, "stream-comp", 1, "evt-comp-1", Map.of());
    FindingId otherVuln =
        ingest(context, "stream-comp", 2, "evt-comp-2", Map.of("vuln_class", "sqli"));
    FindingId otherLocation =
        ingest(context, "stream-comp", 3, "evt-comp-3",
            Map.of("normalized_location_signature", "route:/admin"));
    FindingId otherAsset =
        ingest(context, "stream-comp", 4, "evt-comp-4", Map.of("resource_uid", "deploy-2"));

    assertNotEquals(base, otherVuln);
    assertNotEquals(base, otherLocation);
    assertNotEquals(base, otherAsset);
    assertEquals(4, count("findings", "t-comp"));
    assertEquals(2, count("assets", "t-comp"));
  }

  @Test
  void baselineDriftConflictsAtomicallyWithoutUpdateOrOccurrence() {
    TrustedContext context = new TrustedContext("t-drift", "eng-1");
    ingest(context, "stream-drift", 1, "evt-drift-1", Map.of());

    assertBaselineConflict(context, 2, "evt-drift-2",
        Map.of("verification_key", "check-xss-2"));
    assertBaselineConflict(context, 3, "evt-drift-3", Map.of("check_version", "2.0"));
    assertBaselineConflict(context, 4, "evt-drift-4",
        Map.of("relevant_context", contextMap(8443, false, null)));

    assertEquals(1, count("findings", "t-drift"));
    assertEquals(1, count("finding_occurrences", "t-drift"));
    assertEquals(1, count("source_events", "t-drift"), "conflicting events must roll back");
    assertEquals("check-xss-1", singleFindingColumn("t-drift", "verification_key"));
  }

  @Test
  void nonVersionOnePolicyIsRejectedByTheContract() {
    assertThrows(
        InvalidSourceEventException.class,
        () ->
            findingEvent("evt-policy-1", "stream-policy", 1,
                Map.of("compatibility_policy_version", 2)));
  }

  @Test
  void sameComponentsInAnotherTenantAreADistinctFinding() {
    FindingId tenantA =
        ingest(new TrustedContext("t-iso-a", "eng-1"), "stream-iso-a", 1, "evt-iso-1", Map.of());
    FindingId tenantB =
        ingest(new TrustedContext("t-iso-b", "eng-1"), "stream-iso-b", 1, "evt-iso-2", Map.of());

    assertNotEquals(tenantA, tenantB);
    assertEquals(1, count("findings", "t-iso-a"));
    assertEquals(1, count("findings", "t-iso-b"));
  }

  @Test
  void failureAfterOccurrenceAppendRollsBackEverythingAndRetryCommitsOnce() {
    TrustedContext context = new TrustedContext("t-fail", "eng-1");
    CanonicalSourceEvent canonical = findingEvent("evt-fail-1", "stream-fail", 1, Map.of());

    assertThrows(
        IllegalStateException.class,
        () ->
            transaction.ingest(
                context,
                canonical,
                handle -> {
                  service.recordFindingObservation(
                      handle, context, "evt-fail-1", findingPayload(canonical));
                  throw new IllegalStateException("forced failure after occurrence append");
                }));
    for (String table : new String[] {"source_events", "assets", "findings", "finding_occurrences"}) {
      assertEquals(0, count(table, "t-fail"), table + " must roll back");
    }

    ingestCanonical(context, canonical);
    assertEquals(1, count("findings", "t-fail"));
    assertEquals(1, count("finding_occurrences", "t-fail"));
  }

  @Test
  void exactSourceRetryChangesNothing() {
    TrustedContext context = new TrustedContext("t-retry", "eng-1");
    CanonicalSourceEvent canonical = findingEvent("evt-retry-1", "stream-f-retry", 1, Map.of());
    ingestCanonical(context, canonical);

    IngestResult retry =
        transaction.ingest(
            context, canonical, handle -> fail("work must not run on an exact retry"));

    assertEquals(IngestResult.NO_OP, retry);
    assertEquals(1, count("findings", "t-retry"));
    assertEquals(1, count("finding_occurrences", "t-retry"));
  }

  @Test
  void shuffledCrossEngagementInputsConvergeOnOneFinding() {
    TrustedContext engagementOne = new TrustedContext("t-shuffle", "eng-1");
    TrustedContext engagementTwo = new TrustedContext("t-shuffle", "eng-2");
    FindingId fromLaterEngagement =
        ingest(engagementTwo, "stream-shuffle-2", 1, "evt-shuffle-3", Map.of());
    FindingId fromSecondPosition =
        ingest(engagementOne, "stream-shuffle-1", 2, "evt-shuffle-2", Map.of());
    FindingId fromFirstPosition =
        ingest(engagementOne, "stream-shuffle-1", 1, "evt-shuffle-1", Map.of());

    assertEquals(fromFirstPosition, fromSecondPosition);
    assertEquals(fromFirstPosition, fromLaterEngagement);
    assertEquals(1, count("findings", "t-shuffle"));
    assertEquals(1, count("assets", "t-shuffle"));
    assertEquals(3, count("finding_occurrences", "t-shuffle"));
  }

  private void assertBaselineConflict(
      TrustedContext context, long sequence, String eventId, Map<String, Object> overrides) {
    CanonicalSourceEvent canonical =
        findingEvent(eventId, "stream-drift", sequence, overrides);
    FindingBaselineConflictException conflict =
        assertThrows(
            FindingBaselineConflictException.class,
            () -> ingestCanonical(context, canonical));
    assertTrue(conflict.getMessage().contains(eventId));
    assertFalse(conflict.getMessage().contains("check-xss"), "no payload values in errors");
  }

  private static FindingId ingest(
      TrustedContext context,
      String streamId,
      long sequence,
      String eventId,
      Map<String, Object> payloadOverrides) {
    return ingestCanonical(context, findingEvent(eventId, streamId, sequence, payloadOverrides));
  }

  private static FindingId ingestCanonical(
      TrustedContext context, CanonicalSourceEvent canonical) {
    AtomicReference<FindingId> findingId = new AtomicReference<>();
    transaction.ingest(
        context,
        canonical,
        handle ->
            findingId.set(
                service.recordFindingObservation(
                    handle,
                    context,
                    canonical.event().sourceEventId(),
                    findingPayload(canonical))));
    return findingId.get();
  }

  private static FindingObservation findingPayload(CanonicalSourceEvent canonical) {
    return (FindingObservation) canonical.event().payload();
  }

  private static CanonicalSourceEvent findingEvent(
      String eventId, String streamId, long sequence, Map<String, Object> payloadOverrides) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("cluster_id", "cluster-demo");
    payload.put("resource_kind", "Deployment");
    payload.put("resource_uid", "deploy-1");
    payload.put("vuln_class", "xss");
    payload.put("normalized_location_signature", "route:/login");
    payload.put("verification_key", "check-xss-1");
    payload.put("check_version", "1.0");
    payload.put("relevant_context", contextMap(443, false, null));
    payload.put("compatibility_policy_version", 1);
    payload.putAll(payloadOverrides);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("source_event_id", eventId);
    envelope.put("event_type", "finding_observation");
    envelope.put("source_stream_id", streamId);
    envelope.put("source_sequence", sequence);
    envelope.put("schema_version", 1);
    envelope.put("occurred_at", OCCURRED_AT);
    envelope.put("payload", payload);
    try {
      return codec.decode(MAPPER.writeValueAsString(envelope));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("test envelope failed to serialize", e);
    }
  }

  private static Map<String, Object> contextMap(
      int port, boolean isAddressBound, String targetAddress) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("protocol", "https");
    context.put("port", port);
    context.put("normalized_route", "/login");
    context.put("parameters", Map.of());
    context.put("is_address_bound", isAddressBound);
    if (targetAddress != null) {
      context.put("target_address", targetAddress);
    }
    return context;
  }

  private static int count(String table, String tenantId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT count(*) FROM " + table + " WHERE tenant_id = :tenantId")
                .bind("tenantId", tenantId)
                .mapTo(Integer.class)
                .one());
  }

  private static Map<String, Object> findingRow(String tenantId, FindingId findingId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT verification_key, check_version, match_key_version,"
                        + " compatibility_policy_version FROM findings"
                        + " WHERE tenant_id = :tenantId AND finding_id = :findingId")
                .bind("tenantId", tenantId)
                .bind("findingId", findingId.value())
                .mapToMap()
                .one());
  }

  private static String singleFindingColumn(String tenantId, String column) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT " + column + " FROM findings WHERE tenant_id = :tenantId")
                .bind("tenantId", tenantId)
                .mapTo(String.class)
                .one());
  }
}
