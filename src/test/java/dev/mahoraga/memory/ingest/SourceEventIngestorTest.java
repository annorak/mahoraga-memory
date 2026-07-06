package dev.mahoraga.memory.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.EventType;
import dev.mahoraga.memory.contract.TrustedContext;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the explicit four-case composition against real PostgreSQL: each
 * event type reaches exactly its concrete handler inside the one transaction,
 * exact retries stay no-ops before handler work, an injected failure after the
 * handler rolls back the whole event, and stream-binding conflicts remain
 * rejected through the composed path.
 */
class SourceEventIngestorTest {

  private static final String DATABASE = "source_event_ingestor";

  private static IngestorTestSupport db;

  @BeforeAll
  static void migrateAndConnect() throws SQLException {
    db = IngestorTestSupport.forDatabase(DATABASE);
  }

  @Test
  void assetObservationRoutesToTheAssetHandlerOnly() {
    TrustedContext context = new TrustedContext("t-route-asset", "eng-1");

    IngestResult result =
        db.ingestor.ingest(context, db.assetEvent("evt-ra-1", "stream-ra", 1));

    assertEquals(IngestResult.ACCEPTED, result);
    assertEquals(1, db.count("asset_observations", "t-route-asset"));
    assertEquals(1, db.count("assets", "t-route-asset"));
    assertEquals(0, db.count("findings", "t-route-asset"));
    assertEquals(0, db.count("test_attempts", "t-route-asset"));
  }

  @Test
  void findingObservationRoutesToTheFindingHandlerOnly() {
    TrustedContext context = new TrustedContext("t-route-finding", "eng-1");

    db.ingestor.ingest(context, db.findingEvent("evt-rf-1", "stream-rf", 1));

    assertEquals(1, db.count("findings", "t-route-finding"));
    assertEquals(1, db.count("finding_occurrences", "t-route-finding"));
    assertEquals(0, db.count("asset_observations", "t-route-finding"));
    assertEquals(0, db.count("test_attempts", "t-route-finding"));
  }

  @Test
  void testAttemptRoutesToTheAttemptHandlerOnly() {
    TrustedContext context = new TrustedContext("t-route-attempt", "eng-1");

    db.ingestor.ingest(context, db.attemptEvent("evt-rt-1", "stream-rt", 1));

    assertEquals(1, db.count("test_attempts", "t-route-attempt"));
    assertEquals(0, db.count("findings", "t-route-attempt"));
    assertEquals(0, db.count("asset_observations", "t-route-attempt"));
  }

  @Test
  void completionMarkerProducesNoDomainFact() {
    TrustedContext context = new TrustedContext("t-route-marker", "eng-1");
    db.ingestor.ingest(context, db.assetEvent("evt-rm-1", "stream-rm", 1));

    db.ingestor.ingest(context, db.markerEvent("evt-rm-marker", "stream-rm", 1));

    assertEquals(2, db.count("source_events", "t-route-marker"));
    assertEquals(1, db.count("asset_observations", "t-route-marker"));
    assertEquals(0, db.count("findings", "t-route-marker"));
    assertEquals(0, db.count("test_attempts", "t-route-marker"));
    assertEquals(1L, db.boundary("t-route-marker", "eng-1"));
  }

  @Test
  void exactRetryIsANoOpBeforeHandlerWork() {
    TrustedContext context = new TrustedContext("t-retry", "eng-1");
    CanonicalSourceEvent event = db.assetEvent("evt-retry-1", "stream-ing-retry", 1);
    db.ingestor.ingest(context, event);

    IngestResult retry = db.ingestor.ingest(context, event);

    assertEquals(IngestResult.NO_OP, retry);
    assertEquals(1, db.count("source_events", "t-retry"));
    assertEquals(1, db.count("asset_observations", "t-retry"));
  }

  @Test
  void failureAfterHandlerWorkRollsBackTheWholeEventAndRetrySucceeds() throws SQLException {
    IngestorTestSupport faulty =
        IngestorTestSupport.forDatabase(
            DATABASE,
            (stage, handle) -> {
              if (stage == IngestionFaultHook.Stage.BEFORE_TRANSACTION_RETURN) {
                throw new IllegalStateException("forced failure after domain work");
              }
            });
    TrustedContext context = new TrustedContext("t-fault", "eng-1");
    CanonicalSourceEvent event = db.findingEvent("evt-fault-1", "stream-fault", 1);

    assertThrows(IllegalStateException.class, () -> faulty.ingestor.ingest(context, event));
    for (String table :
        new String[] {"source_events", "assets", "findings", "finding_occurrences"}) {
      assertEquals(0, db.count(table, "t-fault"), table + " must roll back");
    }

    assertEquals(IngestResult.ACCEPTED, db.ingestor.ingest(context, event));
    assertEquals(1, db.count("finding_occurrences", "t-fault"));
  }

  @Test
  void streamReuseUnderAnotherTenantOrEngagementRemainsRejected() {
    TrustedContext owner = new TrustedContext("t-bind-a", "eng-1");
    db.ingestor.ingest(owner, db.assetEvent("evt-bind-1", "stream-bind", 1));

    assertThrows(
        SourceEventConflictException.class,
        () ->
            db.ingestor.ingest(
                new TrustedContext("t-bind-b", "eng-1"),
                db.assetEvent("evt-bind-2", "stream-bind", 2)));
    assertThrows(
        SourceEventConflictException.class,
        () ->
            db.ingestor.ingest(
                new TrustedContext("t-bind-a", "eng-2"),
                db.assetEvent("evt-bind-3", "stream-bind", 2)));
    assertEquals(0, db.count("source_events", "t-bind-b"));
    assertEquals(1, db.count("source_events", "t-bind-a"));
  }

  /** The ingestor switch is exhaustive over the sealed payloads backing these values. */
  @Test
  void exactlyFourEventTypesExist() {
    assertEquals(
        4,
        EventType.values().length,
        "a new event type requires updating the SourceEventIngestor switch");
  }
}
