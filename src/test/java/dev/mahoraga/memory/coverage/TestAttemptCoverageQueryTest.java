package dev.mahoraga.memory.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.finding.FindingId;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the policy-v1 coverage queries against real PostgreSQL: attempts join
 * their finding through persisted values regardless of arrival order, other
 * tenants and mismatched dimensions never join, and only the exact compatible
 * completed negative is resolving evidence.
 */
class TestAttemptCoverageQueryTest {

  private static CoverageIngestSupport db;

  @BeforeAll
  static void migrateAndConnect() throws SQLException {
    db = CoverageIngestSupport.forDatabase("coverage_query");
  }

  @Test
  void attemptBeforeFindingPersistsAndJoinsAfterTheFindingArrives() {
    TrustedContext context = new TrustedContext("t-order", "eng-1");
    RecordedTestAttempt attempt =
        db.ingestAttempt(context, "stream-order", 1, "evt-order-attempt",
            Map.of("relevant_context",
                CoverageIngestSupport.contextMap(
                    443, false, null, CoverageIngestSupport.parameters(false))));
    // The finding arrives later with equivalent context in another parameter
    // order; canonicalization must produce the identical fingerprint.
    FindingId findingId =
        db.ingestFinding(context, "stream-order", 2, "evt-order-finding",
            Map.of("relevant_context",
                CoverageIngestSupport.contextMap(
                    443, false, null, CoverageIngestSupport.parameters(true))));

    assertEquals(
        attempt.relevantContextHash(),
        db.findingColumn("t-order", findingId, "relevant_context_hash"));
    List<RecordedTestAttempt> compatible = db.findCompatible(context, findingId);
    assertEquals(1, compatible.size());
    assertEquals("evt-order-attempt", compatible.get(0).sourceEventId());
    assertEquals(compatible, db.findResolving(context, findingId));
  }

  @Test
  void crossTenantAndForeignKeyAttemptsDoNotJoin() {
    TrustedContext context = new TrustedContext("t-iso", "eng-1");
    FindingId findingId = db.ingestFinding(context, "stream-iso", 1, "evt-iso-finding", Map.of());
    // Identical attempt under another tenant.
    db.ingestAttempt(new TrustedContext("t-iso-other", "eng-1"),
        "stream-iso-other", 1, "evt-iso-foreign", Map.of());
    // Same tenant and verification key against a different canonical asset.
    db.ingestAttempt(context, "stream-iso", 2, "evt-iso-asset",
        Map.of("resource_uid", "deploy-2"));
    // Same tenant and asset with another verification key.
    db.ingestAttempt(context, "stream-iso", 3, "evt-iso-key",
        Map.of("verification_key", "check-other"));

    assertEquals(List.of(), db.findCompatible(context, findingId));
    assertEquals(List.of(), db.findResolving(context, findingId),
        "absent evidence never resolves");
  }

  @Test
  void onlyTheExactCompatibleCompletedNegativeIsResolvingEvidence() {
    TrustedContext context = new TrustedContext("t-resolve", "eng-1");
    FindingId findingId =
        db.ingestFinding(context, "stream-resolve", 1, "evt-res-finding", Map.of());
    db.ingestAttempt(context, "stream-resolve", 2, "evt-res-negative", Map.of());
    db.ingestAttempt(context, "stream-resolve", 3, "evt-res-detected",
        Map.of("result", "detected"));
    db.ingestAttempt(context, "stream-resolve", 4, "evt-res-failed",
        CoverageIngestSupport.outcomeOverrides("failed", null));
    db.ingestAttempt(context, "stream-resolve", 5, "evt-res-blocked",
        CoverageIngestSupport.outcomeOverrides("blocked", "inconclusive"));
    db.ingestAttempt(context, "stream-resolve", 6, "evt-res-partial",
        CoverageIngestSupport.outcomeOverrides("partial", "inconclusive"));
    db.ingestAttempt(context, "stream-resolve", 7, "evt-res-skipped",
        CoverageIngestSupport.outcomeOverrides("skipped", null));
    db.ingestAttempt(context, "stream-resolve", 8, "evt-res-version",
        Map.of("check_version", "2.0"));
    db.ingestAttempt(context, "stream-resolve", 9, "evt-res-port",
        Map.of("relevant_context",
            CoverageIngestSupport.contextMap(
                8443, false, null, CoverageIngestSupport.parameters(false))));
    db.ingestAttempt(context, "stream-resolve", 10, "evt-res-bound",
        Map.of("relevant_context",
            CoverageIngestSupport.contextMap(
                443, true, "10.0.0.10", CoverageIngestSupport.parameters(false))));

    List<String> compatible =
        db.findCompatible(context, findingId).stream()
            .map(RecordedTestAttempt::sourceEventId)
            .toList();
    assertEquals(
        List.of("evt-res-blocked", "evt-res-detected", "evt-res-failed", "evt-res-negative",
            "evt-res-partial", "evt-res-skipped"),
        compatible);
    List<RecordedTestAttempt> resolving = db.findResolving(context, findingId);
    assertEquals(1, resolving.size());
    assertEquals("evt-res-negative", resolving.get(0).sourceEventId());
  }

  @Test
  void queryingAnUnrecordedFindingFailsLoudly() {
    TrustedContext context = new TrustedContext("t-missing", "eng-1");
    FindingId unknown = new FindingId(UUID.randomUUID());

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> db.findCompatible(context, unknown));
    assertTrue(error.getMessage().contains("t-missing"));
  }
}
