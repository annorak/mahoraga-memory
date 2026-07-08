package dev.mahoraga.memory.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import dev.mahoraga.memory.posture.PostureResult;
import dev.mahoraga.memory.posture.PostureResult.CurrentAssessment;
import dev.mahoraga.memory.posture.PostureResult.EpisodeClassification;
import dev.mahoraga.memory.posture.PostureResult.LastVerifiedExposure;
import dev.mahoraga.memory.posture.SelectedFact;
import dev.mahoraga.memory.reporting.Report.FindingResult;
import dev.mahoraga.memory.reporting.Report.MemorySummary;
import dev.mahoraga.memory.reporting.Report.ReportView;
import dev.mahoraga.memory.reporting.Report.StatelessSummary;
import dev.mahoraga.memory.reporting.SemanticFactSet.AssetKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure canonicalization proofs: golden semantic fact bytes and digest, input
 * order and internal-UUID independence, semantic-change sensitivity, loud
 * failure on unknown or duplicate rows, the fixed-order report JSON for both
 * views, and human text carrying the same semantic values as the report.
 */
class ReportCanonicalizationTest {

  private static final String TENANT = "t-report";
  private static final String STREAM = "stream-r";
  private static final String CONTEXT_HASH = "ab".repeat(32);
  private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID FINDING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final AssetKey ASSET_KEY = new AssetKey("cluster-demo", "Deployment", "deploy-1");
  private static final String BOUNDARY_HASH = "11".repeat(32);
  private static final String FACT_DIGEST = "22".repeat(32);

  private static final String GOLDEN_FACTS_JSON =
      "{\"facts\":[{\"fact_kind\":\"finding_occurrence\",\"source_event_id\":\"evt-occ-1\","
          + "\"source_stream_id\":\"stream-r\",\"source_sequence\":1,"
          + "\"occurred_at\":\"2026-01-01T10:00:00Z\",\"cluster_id\":\"cluster-demo\","
          + "\"resource_kind\":\"Deployment\",\"resource_uid\":\"deploy-1\","
          + "\"vuln_class\":\"xss\",\"normalized_location_signature\":\"route:/login\","
          + "\"match_key_version\":1,\"verification_key\":\"check-xss-1\","
          + "\"check_version\":\"1.0\",\"relevant_context_hash\":\"" + CONTEXT_HASH + "\","
          + "\"compatibility_policy_version\":1},"
          + "{\"fact_kind\":\"test_attempt\",\"source_event_id\":\"evt-att-1\","
          + "\"source_stream_id\":\"stream-r\",\"source_sequence\":2,"
          + "\"occurred_at\":\"2026-01-01T10:01:00Z\",\"cluster_id\":\"cluster-demo\","
          + "\"resource_kind\":\"Deployment\",\"resource_uid\":\"deploy-1\","
          + "\"verification_key\":\"check-xss-1\",\"check_version\":\"1.0\","
          + "\"relevant_context_hash\":\"" + CONTEXT_HASH + "\","
          + "\"compatibility_policy_version\":1,\"execution_status\":\"completed\","
          + "\"result\":\"not_detected\"}]}";

  private static final String GOLDEN_FACTS_DIGEST =
      "d048baa97b02d2aa514e3962b3de66a65186bb09d4aab284bb9046502e69bf76";

  private static final String GOLDEN_STATELESS_REPORT_JSON =
      "{\"report_view\":\"STATELESS\",\"knowledge_boundary_hash\":\"" + BOUNDARY_HASH + "\","
          + "\"current_engagement_id\":\"eng-2\",\"policy_bundle_version\":1,"
          + "\"current_engagement_fact_digest\":\"" + FACT_DIGEST + "\","
          + "\"summary\":{\"detected\":3,\"not_detected\":1,\"partial\":1}}";

  private static final String GOLDEN_STATELESS_REPORT_DIGEST =
      "de02bde0b104988a135703911d540b7c23da2a311b7c928c0c45441e3d2d8ea0";

  @Test
  void canonicalFactSetMatchesGoldenBytesAndDigest() {
    SemanticFactSet set = SemanticFactSet.of(goldenFacts(), Map.of(ASSET_ID, ASSET_KEY));

    assertEquals(GOLDEN_FACTS_JSON, set.canonicalJson());
    assertEquals(GOLDEN_FACTS_DIGEST, set.digest());
  }

  @Test
  void factInputOrderDoesNotChangeCanonicalBytes() {
    List<SelectedFact> reversed = List.of(goldenFacts().get(1), goldenFacts().get(0));

    SemanticFactSet set = SemanticFactSet.of(reversed, Map.of(ASSET_ID, ASSET_KEY));

    assertEquals(GOLDEN_FACTS_JSON, set.canonicalJson());
    assertEquals(GOLDEN_FACTS_DIGEST, set.digest());
  }

  @Test
  void internalIdSubstitutionDoesNotChangeCanonicalBytes() {
    UUID otherAsset = UUID.randomUUID();
    UUID otherFinding = UUID.randomUUID();
    List<SelectedFact> substituted =
        List.of(
            occurrence("evt-occ-1", 1, "2026-01-01T10:00:00Z", otherFinding, otherAsset),
            attempt(
                "evt-att-1",
                2,
                "2026-01-01T10:01:00Z",
                otherAsset,
                ExecutionStatus.COMPLETED,
                TestResult.NOT_DETECTED));

    SemanticFactSet set = SemanticFactSet.of(substituted, Map.of(otherAsset, ASSET_KEY));

    assertEquals(GOLDEN_FACTS_JSON, set.canonicalJson());
    assertEquals(GOLDEN_FACTS_DIGEST, set.digest());
  }

  @Test
  void everySemanticChangeChangesTheDigest() {
    List<List<SelectedFact>> variants =
        List.of(
            List.of(
                occurrence("evt-occ-1", 1, "2026-01-01T10:00:01Z", FINDING_ID, ASSET_ID),
                goldenFacts().get(1)),
            List.of(
                occurrence("evt-occ-1", 3, "2026-01-01T10:00:00Z", FINDING_ID, ASSET_ID),
                goldenFacts().get(1)),
            List.of(
                goldenFacts().get(0),
                attempt(
                    "evt-att-1",
                    2,
                    "2026-01-01T10:01:00Z",
                    ASSET_ID,
                    ExecutionStatus.PARTIAL,
                    null)),
            List.of(
                goldenFacts().get(0),
                attempt(
                    "evt-att-1",
                    2,
                    "2026-01-01T10:01:00Z",
                    ASSET_ID,
                    ExecutionStatus.COMPLETED,
                    TestResult.DETECTED)),
            List.of(goldenFacts().get(0)));

    for (List<SelectedFact> variant : variants) {
      SemanticFactSet set = SemanticFactSet.of(variant, Map.of(ASSET_ID, ASSET_KEY));
      assertNotEquals(GOLDEN_FACTS_DIGEST, set.digest());
    }
  }

  @Test
  void changedAuthoritativeAssetKeyChangesTheDigest() {
    AssetKey otherKey = new AssetKey("cluster-demo", "Deployment", "deploy-2");

    SemanticFactSet set = SemanticFactSet.of(goldenFacts(), Map.of(ASSET_ID, otherKey));

    assertNotEquals(GOLDEN_FACTS_DIGEST, set.digest());
  }

  @Test
  void missingAssetKeyFailsInsteadOfDropping() {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> SemanticFactSet.of(goldenFacts(), Map.of()));

    assertTrue(failure.getMessage().contains("evt-occ-1"));
  }

  @Test
  void duplicateSemanticFactFailsInsteadOfDropping() {
    List<SelectedFact> duplicated = List.of(goldenFacts().get(0), goldenFacts().get(0));

    assertThrows(
        IllegalArgumentException.class,
        () -> SemanticFactSet.of(duplicated, Map.of(ASSET_ID, ASSET_KEY)));
  }

  @Test
  void statelessReportJsonMatchesGoldenAndDigestIsRepeatable() {
    Report report = statelessReport();

    assertEquals(GOLDEN_STATELESS_REPORT_JSON, ReportRenderer.canonicalJson(report));
    assertEquals(GOLDEN_STATELESS_REPORT_DIGEST, ReportRenderer.semanticDigest(report));
    assertEquals(ReportRenderer.semanticDigest(report), ReportRenderer.semanticDigest(report));
  }

  @Test
  void memoryReportJsonCarriesFixedOrderCountsAndFindings() {
    Report report = memoryReport();

    String json = ReportRenderer.canonicalJson(report);
    assertTrue(
        json.contains(
            "\"classification_counts\":{\"NEW\":0,\"STILL_OPEN\":1,\"VERIFIED_RESOLVED\":0,"
                + "\"REGRESSED\":1,\"NOT_RETESTED\":0,\"INCONCLUSIVE\":0}"));
    // The admin finding sorts before the login finding by stable identity,
    // independent of the construction order used below.
    assertTrue(
        json.indexOf("\"verification_key\":\"check-admin-1\"")
            < json.indexOf("\"verification_key\":\"check-xss-1\""));
    assertTrue(json.contains("\"episode_classification\":\"REGRESSED\""));
    assertTrue(json.contains("\"current_assessment\":\"DETECTED\""));
    assertTrue(json.contains("\"last_verified_exposure\":\"OPEN\""));
  }

  @Test
  void reportDigestsDifferBetweenViewsWhileFactDigestIsShared() {
    Report stateless = statelessReport();
    Report memory = memoryReport();

    assertEquals(
        stateless.currentEngagementFactDigest(), memory.currentEngagementFactDigest());
    assertNotEquals(
        ReportRenderer.semanticDigest(stateless), ReportRenderer.semanticDigest(memory));
  }

  @Test
  void reportRejectsMismatchedViewSummaryAndPolicyVersion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Report(
                ReportView.MEMORY,
                BOUNDARY_HASH,
                "eng-2",
                Report.POLICY_BUNDLE_VERSION,
                FACT_DIGEST,
                new StatelessSummary(3, 1, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Report(
                ReportView.STATELESS,
                BOUNDARY_HASH,
                "eng-2",
                2,
                FACT_DIGEST,
                new StatelessSummary(3, 1, 1)));
  }

  @Test
  void memorySummarySortsFindingsAndRejectsDuplicates() {
    MemorySummary summary = new MemorySummary(List.of(loginResult(), adminResult()));

    assertEquals(
        List.of("check-admin-1", "check-xss-1"),
        summary.findings().stream().map(FindingResult::verificationKey).toList());
    assertThrows(
        IllegalArgumentException.class,
        () -> new MemorySummary(List.of(adminResult(), adminResult())));
  }

  @Test
  void humanTextCarriesTheSameSemanticValuesAsTheDto() {
    String statelessText = ReportRenderer.humanText(statelessReport());
    assertTrue(statelessText.contains("Detected: 3"));
    assertTrue(statelessText.contains("Not detected: 1"));
    assertTrue(statelessText.contains("Partial: 1"));
    assertTrue(statelessText.contains("Findings with no eng-2 fact: not representable"));
    assertTrue(statelessText.contains("Longitudinal classifications: unavailable"));
    assertTrue(statelessText.contains(FACT_DIGEST));
    assertTrue(statelessText.contains(BOUNDARY_HASH));
    for (EpisodeClassification classification : MemorySummary.CLASSIFICATION_REPORT_ORDER) {
      assertFalse(statelessText.contains(classification.name() + ":"));
    }

    String memoryText = ReportRenderer.humanText(memoryReport());
    assertTrue(memoryText.contains("STILL_OPEN: 1"));
    assertTrue(memoryText.contains("REGRESSED: 1"));
    assertTrue(memoryText.contains("NOT_RETESTED: 0"));
    assertTrue(
        memoryText.contains(
            "check-admin-1 [broken_authz @ sig-admin, cluster-demo/Deployment/deploy-1]:"
                + " episode REGRESSED, current DETECTED, last verified exposure OPEN"));
    assertTrue(memoryText.contains(FACT_DIGEST));
  }

  private static List<SelectedFact> goldenFacts() {
    return List.of(
        occurrence("evt-occ-1", 1, "2026-01-01T10:00:00Z", FINDING_ID, ASSET_ID),
        attempt(
            "evt-att-1",
            2,
            "2026-01-01T10:01:00Z",
            ASSET_ID,
            ExecutionStatus.COMPLETED,
            TestResult.NOT_DETECTED));
  }

  private static SelectedFact.FindingOccurrence occurrence(
      String eventId, long sequence, String occurredAt, UUID findingId, UUID assetId) {
    return new SelectedFact.FindingOccurrence(
        TENANT,
        "eng-2",
        eventId,
        STREAM,
        sequence,
        Instant.parse(occurredAt),
        findingId,
        assetId,
        "xss",
        "route:/login",
        1,
        "check-xss-1",
        "1.0",
        CONTEXT_HASH,
        1);
  }

  private static SelectedFact.TestAttempt attempt(
      String eventId,
      long sequence,
      String occurredAt,
      UUID assetId,
      ExecutionStatus status,
      TestResult result) {
    return new SelectedFact.TestAttempt(
        TENANT,
        "eng-2",
        eventId,
        STREAM,
        sequence,
        Instant.parse(occurredAt),
        assetId,
        "check-xss-1",
        "1.0",
        CONTEXT_HASH,
        1,
        status,
        result);
  }

  private static Report statelessReport() {
    return new Report(
        ReportView.STATELESS,
        BOUNDARY_HASH,
        "eng-2",
        Report.POLICY_BUNDLE_VERSION,
        FACT_DIGEST,
        new StatelessSummary(3, 1, 1));
  }

  private static Report memoryReport() {
    return new Report(
        ReportView.MEMORY,
        BOUNDARY_HASH,
        "eng-2",
        Report.POLICY_BUNDLE_VERSION,
        FACT_DIGEST,
        new MemorySummary(List.of(loginResult(), adminResult())));
  }

  private static FindingResult loginResult() {
    return new FindingResult(
        "cluster-demo",
        "Deployment",
        "deploy-1",
        "xss",
        "route:/login",
        1,
        "check-xss-1",
        new PostureResult(
            LastVerifiedExposure.OPEN,
            CurrentAssessment.DETECTED,
            EpisodeClassification.STILL_OPEN));
  }

  private static FindingResult adminResult() {
    return new FindingResult(
        "cluster-demo",
        "Deployment",
        "deploy-1",
        "broken_authz",
        "sig-admin",
        1,
        "check-admin-1",
        new PostureResult(
            LastVerifiedExposure.OPEN,
            CurrentAssessment.DETECTED,
            EpisodeClassification.REGRESSED));
  }
}
