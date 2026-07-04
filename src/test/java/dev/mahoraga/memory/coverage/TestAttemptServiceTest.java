package dev.mahoraga.memory.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.InvalidSourceEventException;
import dev.mahoraga.memory.contract.SourcePayload.TestAttempt;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.finding.RelevantContextFingerprint;
import dev.mahoraga.memory.ingest.IngestResult;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves immutable test-attempt persistence against real PostgreSQL through
 * the existing ingestion transaction: the server resolves the asset and
 * computes the fingerprint, every legal outcome persists, forbidden outcomes
 * never reach the database, retries are no-ops, and rollback leaves no partial
 * state.
 */
class TestAttemptServiceTest {

  private static final String[][] LEGAL_OUTCOMES = {
    {"completed", "detected"},
    {"completed", "not_detected"},
    {"failed", null},
    {"failed", "inconclusive"},
    {"blocked", null},
    {"blocked", "inconclusive"},
    {"partial", null},
    {"partial", "inconclusive"},
    {"skipped", null},
    {"skipped", "inconclusive"},
  };
  private static final String[][] FORBIDDEN_OUTCOMES = {
    {"completed", null},
    {"completed", "inconclusive"},
    {"failed", "detected"},
    {"failed", "not_detected"},
    {"blocked", "detected"},
    {"blocked", "not_detected"},
    {"partial", "detected"},
    {"partial", "not_detected"},
    {"skipped", "detected"},
    {"skipped", "not_detected"},
  };

  private static CoverageIngestSupport db;

  @BeforeAll
  static void migrateAndConnect() throws SQLException {
    db = CoverageIngestSupport.forDatabase("test_attempts");
  }

  @Test
  void attemptResolvesItsAssetAndPersistsServerComputedValuesOnly() {
    TrustedContext context = new TrustedContext("t-record", "eng-1");
    RecordedTestAttempt recorded =
        db.ingestAttempt(context, "stream-record", 1, "evt-record-1",
            Map.of("result", "detected"));

    assertEquals(1, db.count("assets", "t-record"));
    assertEquals(1, db.count("test_attempts", "t-record"));
    Map<String, Object> row = db.attemptRow("t-record", "evt-record-1");
    assertEquals(recorded.canonicalAssetId(), row.get("canonical_asset_id"));
    assertEquals(db.assetId("t-record"), row.get("canonical_asset_id"));
    TestAttempt payload =
        CoverageIngestSupport.attemptPayload(
            db.attemptEvent("evt-record-1", "stream-record", 1, Map.of("result", "detected")));
    assertEquals(
        RelevantContextFingerprint.hash(payload.relevantContext()),
        row.get("relevant_context_hash"));
    assertEquals(0, db.count("findings", "t-record"), "a detected attempt synthesizes no finding");
    assertEquals(0, db.count("finding_occurrences", "t-record"));
  }

  @Test
  void everyLegalStatusResultPairPersists() {
    TrustedContext context = new TrustedContext("t-legal", "eng-1");
    long sequence = 1;
    for (String[] outcome : LEGAL_OUTCOMES) {
      String eventId = "evt-legal-" + sequence;
      db.ingestAttempt(context, "stream-legal", sequence, eventId,
          CoverageIngestSupport.outcomeOverrides(outcome[0], outcome[1]));
      Map<String, Object> row = db.attemptRow("t-legal", eventId);
      assertEquals(outcome[0], row.get("execution_status"));
      assertEquals(outcome[1], row.get("result"));
      sequence++;
    }
    assertEquals(LEGAL_OUTCOMES.length, db.count("test_attempts", "t-legal"));
  }

  /** The database constraint for the same pairs is proven by the schema tests. */
  @Test
  void everyForbiddenStatusResultPairIsRejectedByTheContract() {
    for (String[] outcome : FORBIDDEN_OUTCOMES) {
      assertThrows(
          InvalidSourceEventException.class,
          () ->
              db.attemptEvent("evt-forbidden", "stream-forbidden", 1,
                  CoverageIngestSupport.outcomeOverrides(outcome[0], outcome[1])),
          outcome[0] + "/" + outcome[1] + " must be rejected");
    }
    assertEquals(0, db.count("test_attempts", "t-forbidden"));
  }

  @Test
  void exactRetryAddsZeroRows() {
    TrustedContext context = new TrustedContext("t-retry", "eng-1");
    CanonicalSourceEvent canonical = db.attemptEvent("evt-retry-1", "stream-a-retry", 1, Map.of());
    db.ingestCanonical(context, canonical);

    IngestResult retry =
        db.transaction.ingest(
            context, canonical, handle -> fail("work must not run on an exact retry"));

    assertEquals(IngestResult.NO_OP, retry);
    assertEquals(1, db.count("test_attempts", "t-retry"));
  }

  @Test
  void failureAfterAttemptInsertRollsBackEverythingAndRetrySucceeds() {
    TrustedContext context = new TrustedContext("t-fail", "eng-1");
    CanonicalSourceEvent canonical = db.attemptEvent("evt-fail-1", "stream-fail", 1, Map.of());

    assertThrows(
        IllegalStateException.class,
        () ->
            db.transaction.ingest(
                context,
                canonical,
                handle -> {
                  db.service.recordTestAttempt(
                      handle, context, "evt-fail-1", CoverageIngestSupport.attemptPayload(canonical));
                  throw new IllegalStateException("forced failure after attempt insert");
                }));
    // Each count opens a fresh handle; none may observe partial state.
    for (String table : new String[] {"source_events", "assets", "test_attempts"}) {
      assertEquals(0, db.count(table, "t-fail"), table + " must roll back");
    }

    db.ingestCanonical(context, canonical);
    assertEquals(1, db.count("test_attempts", "t-fail"));
  }
}
