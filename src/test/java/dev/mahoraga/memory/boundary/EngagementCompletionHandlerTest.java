package dev.mahoraga.memory.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.InvalidSourceEventException;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.ingest.IngestionFaultHook;
import dev.mahoraga.memory.ingest.IngestorTestSupport;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the durable completion protocol against real PostgreSQL through the
 * composed ingestor: contiguous 1..N plus one marker at N+1 finalizes,
 * incomplete markers stay pending until a gap fill, out-of-protocol positions
 * reject without poisoning the stream, the boundary is write-once, and pending
 * state survives restart from persisted rows alone.
 */
class EngagementCompletionHandlerTest {

  private static final String DATABASE = "engagement_completion";

  private static IngestorTestSupport db;

  @BeforeAll
  static void migrateAndConnect() throws SQLException {
    db = IngestorTestSupport.forDatabase(DATABASE);
  }

  @Test
  void completeStreamFinalizesInTheMarkerTransaction() {
    TrustedContext context = new TrustedContext("t-done", "eng-1");
    for (long position = 1; position <= 3; position++) {
      db.ingestor.ingest(context, db.assetEvent("evt-done-" + position, "stream-done", position));
      assertNull(db.boundary("t-done", "eng-1"), "no boundary before the marker");
    }

    db.ingestor.ingest(context, db.markerEvent("evt-done-marker", "stream-done", 3));

    assertEquals(3L, db.boundary("t-done", "eng-1"));
  }

  @Test
  void missingFirstMiddleOrFinalPositionLeavesMarkerPendingUntilGapFill() {
    for (long missing = 1; missing <= 3; missing++) {
      String tenant = "t-gap-" + missing;
      String stream = "stream-gap-" + missing;
      TrustedContext context = new TrustedContext(tenant, "eng-1");
      for (long position = 1; position <= 3; position++) {
        if (position != missing) {
          db.ingestor.ingest(
              context, db.assetEvent("evt-gap-" + missing + "-" + position, stream, position));
        }
      }

      IngestResult marker =
          db.ingestor.ingest(context, db.markerEvent("evt-gap-" + missing + "-marker", stream, 3));
      assertEquals(IngestResult.ACCEPTED, marker, "an incomplete marker commits as pending");
      assertNull(db.boundary(tenant, "eng-1"), "missing " + missing + " must block finalization");

      db.ingestor.ingest(
          context, db.assetEvent("evt-gap-" + missing + "-" + missing, stream, missing));
      assertEquals(3L, db.boundary(tenant, "eng-1"), "gap fill must finalize without replay");
    }
  }

  @Test
  void markerLocalContractRulesRemainEnforced() {
    assertThrows(
        InvalidSourceEventException.class,
        () -> db.markerEventAt("evt-bad-slot", "stream-bad", 5, 3),
        "marker sequence must be last_data_sequence + 1");
    assertThrows(
        InvalidSourceEventException.class,
        () -> db.markerEventAt("evt-bad-limit", "stream-bad", 1, 0),
        "last_data_sequence must be positive");
  }

  @Test
  void markerIsRejectedWhenAPositionBeyondItsSlotExists() {
    TrustedContext context = new TrustedContext("t-beyond", "eng-1");
    db.ingestor.ingest(context, db.assetEvent("evt-beyond-1", "stream-beyond", 1));
    db.ingestor.ingest(context, db.assetEvent("evt-beyond-5", "stream-beyond", 5));

    assertThrows(
        EngagementCompletionConflictException.class,
        () -> db.ingestor.ingest(context, db.markerEvent("evt-beyond-marker", "stream-beyond", 3)));

    assertFalse(db.eventExists("t-beyond", "evt-beyond-marker"), "rejected marker rolls back");
    assertNull(db.boundary("t-beyond", "eng-1"));
    // The stream is not poisoned: completing the true range still finalizes.
    db.ingestor.ingest(context, db.assetEvent("evt-beyond-2", "stream-beyond", 2));
    db.ingestor.ingest(context, db.assetEvent("evt-beyond-3", "stream-beyond", 3));
    db.ingestor.ingest(context, db.assetEvent("evt-beyond-4", "stream-beyond", 4));
    db.ingestor.ingest(context, db.markerEvent("evt-beyond-real", "stream-beyond", 5));
    assertEquals(5L, db.boundary("t-beyond", "eng-1"));
  }

  @Test
  void positionBeyondAPendingMarkerIsRejectedAndDoesNotPersist() {
    TrustedContext context = new TrustedContext("t-late", "eng-1");
    db.ingestor.ingest(context, db.assetEvent("evt-late-1", "stream-late", 1));
    db.ingestor.ingest(context, db.markerEvent("evt-late-marker", "stream-late", 2));
    assertNull(db.boundary("t-late", "eng-1"), "marker pending on missing position 2");

    assertThrows(
        EngagementCompletionConflictException.class,
        () -> db.ingestor.ingest(context, db.assetEvent("evt-late-4", "stream-late", 4)));

    assertFalse(db.eventExists("t-late", "evt-late-4"));
    // The rejection does not poison the stream: the real gap fill finalizes.
    db.ingestor.ingest(context, db.assetEvent("evt-late-2", "stream-late", 2));
    assertEquals(2L, db.boundary("t-late", "eng-1"));
  }

  @Test
  void everyEventAfterFinalizationIsRejectedWhileExactRetriesStayNoOps() {
    TrustedContext context = new TrustedContext("t-closed", "eng-1");
    CanonicalSourceEvent data = db.assetEvent("evt-closed-1", "stream-closed", 1);
    CanonicalSourceEvent marker = db.markerEvent("evt-closed-marker", "stream-closed", 1);
    db.ingestor.ingest(context, data);
    db.ingestor.ingest(context, marker);
    assertEquals(1L, db.boundary("t-closed", "eng-1"));

    assertThrows(
        EngagementCompletionConflictException.class,
        () -> db.ingestor.ingest(context, db.assetEvent("evt-closed-3", "stream-closed", 3)));
    assertFalse(db.eventExists("t-closed", "evt-closed-3"));

    assertEquals(IngestResult.NO_OP, db.ingestor.ingest(context, data));
    assertEquals(IngestResult.NO_OP, db.ingestor.ingest(context, marker));
    assertEquals(1L, db.boundary("t-closed", "eng-1"));
  }

  @Test
  void overlappingPositionsInTwoStreamsRemainIndependent() {
    TrustedContext first = new TrustedContext("t-two", "eng-1");
    TrustedContext second = new TrustedContext("t-two", "eng-2");
    db.ingestor.ingest(first, db.assetEvent("evt-two-a1", "stream-two-1", 1));
    db.ingestor.ingest(second, db.assetEvent("evt-two-b1", "stream-two-2", 1));
    db.ingestor.ingest(second, db.assetEvent("evt-two-b2", "stream-two-2", 2));

    db.ingestor.ingest(first, db.markerEvent("evt-two-a-marker", "stream-two-1", 1));

    assertEquals(1L, db.boundary("t-two", "eng-1"));
    assertNull(db.boundary("t-two", "eng-2"), "the sibling stream must stay open");
    db.ingestor.ingest(second, db.markerEvent("evt-two-b-marker", "stream-two-2", 2));
    assertEquals(2L, db.boundary("t-two", "eng-2"));
  }

  @Test
  void restartBetweenMarkerAndGapFillStillFinalizes() throws SQLException {
    TrustedContext context = new TrustedContext("t-restart", "eng-1");
    db.ingestor.ingest(context, db.assetEvent("evt-restart-2", "stream-restart", 2));
    db.ingestor.ingest(context, db.markerEvent("evt-restart-marker", "stream-restart", 2));
    assertNull(db.boundary("t-restart", "eng-1"));

    // A fresh Jdbi and ingestor hold no in-memory pending state; the durable
    // marker and position rows alone must drive finalization.
    IngestorTestSupport restarted = IngestorTestSupport.forDatabase(DATABASE);
    restarted.ingestor.ingest(context, restarted.assetEvent("evt-restart-1", "stream-restart", 1));

    assertEquals(2L, restarted.boundary("t-restart", "eng-1"));
  }

  @Test
  void failureAfterTheBoundaryUpdateRollsBackMarkerAndFinalization() throws SQLException {
    IngestorTestSupport faulty =
        IngestorTestSupport.forDatabase(
            DATABASE,
            (stage, handle) -> {
              if (stage == IngestionFaultHook.Stage.AFTER_ENGAGEMENT_FINALIZATION_WRITE) {
                throw new IllegalStateException("forced failure after boundary update");
              }
            });
    TrustedContext context = new TrustedContext("t-boundfail", "eng-1");
    db.ingestor.ingest(context, db.assetEvent("evt-bf-1", "stream-bf", 1));

    assertThrows(
        IllegalStateException.class,
        () -> faulty.ingestor.ingest(context, faulty.markerEvent("evt-bf-marker", "stream-bf", 1)));

    assertFalse(db.eventExists("t-boundfail", "evt-bf-marker"), "marker insert rolls back");
    assertNull(db.boundary("t-boundfail", "eng-1"), "finalization rolls back with the marker");
    db.ingestor.ingest(context, db.markerEvent("evt-bf-marker", "stream-bf", 1));
    assertEquals(1L, db.boundary("t-boundfail", "eng-1"));
  }
}
