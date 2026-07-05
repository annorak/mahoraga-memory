package dev.mahoraga.memory.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.boundary.BoundaryPosition;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.fixture.FixtureEventSet;
import dev.mahoraga.memory.fixture.FixtureLoader;
import dev.mahoraga.memory.ingest.IngestorTestSupport;
import dev.mahoraga.memory.posture.PostureResult;
import dev.mahoraga.memory.posture.PostureResult.CurrentAssessment;
import dev.mahoraga.memory.posture.PostureResult.EpisodeClassification;
import dev.mahoraga.memory.posture.PostureResult.LastVerifiedExposure;
import dev.mahoraga.memory.reporting.Report.FindingResult;
import dev.mahoraga.memory.reporting.Report.MemorySummary;
import dev.mahoraga.memory.reporting.Report.ReportView;
import dev.mahoraga.memory.reporting.Report.StatelessSummary;
import io.dropwizard.validation.BaseValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves both report views against real PostgreSQL over the ingested v1
 * fixture bundle: exact stateless 3/1/1 with no longitudinal vocabulary and no
 * F-UNTESTED subject, the memory view's exactly-one-of-each-six with separate
 * dimensions, one shared current-engagement fact digest across views,
 * finalized-boundary and tenant rejection before any query, and restart
 * determinism of canonical bytes and digests.
 */
class ReportServiceTest {

  private static final String DATABASE = "deterministic_reports";
  private static final String TENANT = "tenant-acme";
  private static final TrustedContext E2_CONTEXT = new TrustedContext(TENANT, "engagement-2");
  private static final KnowledgeBoundary STATELESS_BOUNDARY =
      KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-e2", 9)));
  private static final KnowledgeBoundary MEMORY_BOUNDARY =
      KnowledgeBoundary.of(
          List.of(new BoundaryPosition("stream-e1", 7), new BoundaryPosition("stream-e2", 9)));

  private static IngestorTestSupport db;
  private static final ReportService service = new ReportService();

  @BeforeAll
  static void ingestFixtureBundleAndOneUnfinalizedStream() throws SQLException {
    db = IngestorTestSupport.forDatabase(DATABASE);
    ingest(loadEventSet("engagement-e1.json"));
    ingest(loadEventSet("engagement-e2-planner-events.json"));
    ingest(loadEventSet("engagement-e2-background-events.json"));
    ingest(loadEventSet("engagement-e2-completion.json"));
    // One extra engagement with data but no completion marker, so an
    // unfinalized stream exists for boundary-rejection proofs.
    db.ingestor.ingest(
        new TrustedContext(TENANT, "engagement-3"), db.assetEvent("e3-asset", "stream-e3", 1));
  }

  @Test
  void statelessViewCountsExactlyThreeOneOne() {
    Report report = stateless();

    StatelessSummary summary = assertInstanceOf(StatelessSummary.class, report.summary());
    assertEquals(ReportView.STATELESS, report.view());
    assertEquals("engagement-2", report.currentEngagementId());
    assertEquals(Report.POLICY_BUNDLE_VERSION, report.policyBundleVersion());
    // Three semantic detections; the two completed detected attempts pair with
    // their occurrences and do not double count; the standalone completed
    // negative and the single incomplete attempt count once each.
    assertEquals(3, summary.detectedCount());
    assertEquals(1, summary.notDetectedCount());
    assertEquals(1, summary.partialCount());
  }

  @Test
  void statelessViewHasNoLongitudinalVocabularyAndCannotRepresentTheUntestedFinding() {
    Report report = stateless();
    String canonicalJson = ReportRenderer.canonicalJson(report);

    for (EpisodeClassification classification : MemorySummary.CLASSIFICATION_REPORT_ORDER) {
      assertFalse(
          canonicalJson.contains(classification.name()),
          "stateless canonical JSON must not contain " + classification.name());
    }
    // The untested finding has no current-engagement fact, so no stateless
    // rendering can even name its verification key.
    assertFalse(canonicalJson.contains("check-upload-rce"));
    String humanText = ReportRenderer.humanText(report);
    assertTrue(humanText.contains("Findings with no engagement-2 fact: not representable"));
    assertTrue(humanText.contains("Longitudinal classifications: unavailable"));
  }

  @Test
  void memoryViewYieldsExactlyOneOfEachClassification() {
    MemorySummary summary = assertInstanceOf(MemorySummary.class, memory().summary());

    assertEquals(6, summary.findings().size());
    for (EpisodeClassification classification : MemorySummary.CLASSIFICATION_REPORT_ORDER) {
      assertEquals(
          1,
          summary.classificationCount(classification),
          "expected exactly one " + classification.name());
    }
  }

  @Test
  void memoryViewKeepsTheThreePostureDimensionsSeparate() {
    MemorySummary summary = assertInstanceOf(MemorySummary.class, memory().summary());
    Map<String, PostureResult> byKey = new HashMap<>();
    for (FindingResult result : summary.findings()) {
      byKey.put(result.verificationKey(), result.posture());
    }

    PostureResult regressed = byKey.get("check-admin-authz");
    assertEquals(EpisodeClassification.REGRESSED, regressed.episodeClassification());
    assertEquals(CurrentAssessment.DETECTED, regressed.currentAssessment());
    assertEquals(LastVerifiedExposure.OPEN, regressed.lastVerifiedExposure());

    PostureResult untested = byKey.get("check-upload-rce");
    assertEquals(EpisodeClassification.NOT_RETESTED, untested.episodeClassification());
    assertEquals(CurrentAssessment.NOT_RETESTED, untested.currentAssessment());
    // A missing observation is not evidence of remediation: the prior open
    // exposure survives the untested current engagement.
    assertEquals(LastVerifiedExposure.OPEN, untested.lastVerifiedExposure());

    PostureResult resolved = byKey.get("check-search-xss");
    assertEquals(EpisodeClassification.VERIFIED_RESOLVED, resolved.episodeClassification());
    assertEquals(CurrentAssessment.NOT_DETECTED, resolved.currentAssessment());
    assertEquals(LastVerifiedExposure.VERIFIED_RESOLVED, resolved.lastVerifiedExposure());
  }

  @Test
  void bothViewsShareOneCurrentEngagementFactDigestWithDifferentReportDigests() {
    Report stateless = stateless();
    Report memory = memory();

    assertEquals(
        stateless.currentEngagementFactDigest(), memory.currentEngagementFactDigest());
    assertNotEquals(
        ReportRenderer.semanticDigest(stateless), ReportRenderer.semanticDigest(memory));
    assertNotEquals(stateless.knowledgeBoundaryHash(), memory.knowledgeBoundaryHash());
  }

  @Test
  void unknownUnfinalizedAndPartialBoundariesRejectBeforeRendering() {
    assertBoundaryRejected("stream-unknown", 1);
    // Data exists at position 1 but the engagement has no completion marker.
    assertBoundaryRejected("stream-e3", 1);
    // A limit below the write-once finalized boundary is an incomplete view.
    assertBoundaryRejected("stream-e2", 5);
  }

  @Test
  void viewScopeMismatchesReject() {
    // The stateless view must not accept prior-engagement streams.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            db.jdbi.withHandle(
                handle -> service.statelessReport(handle, E2_CONTEXT, MEMORY_BOUNDARY)));
    // The memory view must include the current engagement's stream.
    KnowledgeBoundary e1Only =
        KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-e1", 7)));
    assertThrows(
        IllegalArgumentException.class,
        () -> db.jdbi.withHandle(handle -> service.memoryReport(handle, E2_CONTEXT, e1Only)));
  }

  @Test
  void anotherTenantCannotRenderThisTenantsBoundary() {
    TrustedContext otherTenant = new TrustedContext("tenant-other", "engagement-2");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            db.jdbi.withHandle(
                handle -> service.statelessReport(handle, otherTenant, STATELESS_BOUNDARY)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            db.jdbi.withHandle(
                handle -> service.memoryReport(handle, otherTenant, MEMORY_BOUNDARY)));
  }

  @Test
  void restartYieldsEqualCanonicalBytesAndDigests() throws SQLException {
    Report statelessBefore = stateless();
    Report memoryBefore = memory();

    IngestorTestSupport restarted = IngestorTestSupport.forDatabase(DATABASE);
    ReportService restartedService = new ReportService();
    Report statelessAfter =
        restarted.jdbi.withHandle(
            handle -> restartedService.statelessReport(handle, E2_CONTEXT, STATELESS_BOUNDARY));
    Report memoryAfter =
        restarted.jdbi.withHandle(
            handle -> restartedService.memoryReport(handle, E2_CONTEXT, MEMORY_BOUNDARY));

    assertEquals(
        ReportRenderer.canonicalJson(statelessBefore),
        ReportRenderer.canonicalJson(statelessAfter));
    assertEquals(
        ReportRenderer.canonicalJson(memoryBefore), ReportRenderer.canonicalJson(memoryAfter));
    assertEquals(
        ReportRenderer.semanticDigest(memoryBefore), ReportRenderer.semanticDigest(memoryAfter));
    assertEquals(
        statelessBefore.currentEngagementFactDigest(),
        statelessAfter.currentEngagementFactDigest());
  }

  private static Report stateless() {
    return db.jdbi.withHandle(
        handle -> service.statelessReport(handle, E2_CONTEXT, STATELESS_BOUNDARY));
  }

  private static Report memory() {
    return db.jdbi.withHandle(handle -> service.memoryReport(handle, E2_CONTEXT, MEMORY_BOUNDARY));
  }

  private static void assertBoundaryRejected(String streamId, long limit) {
    KnowledgeBoundary boundary =
        KnowledgeBoundary.of(List.of(new BoundaryPosition(streamId, limit)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            db.jdbi.withHandle(handle -> service.memoryReport(handle, E2_CONTEXT, boundary)));
  }

  private static void ingest(FixtureEventSet eventSet) {
    TrustedContext context = eventSet.trustedContext();
    for (CanonicalSourceEvent event : eventSet.events()) {
      db.ingestor.ingest(context, event);
    }
  }

  private static FixtureEventSet loadEventSet(String resourceName) {
    FixtureLoader loader =
        new FixtureLoader(
            IngestorTestSupport.MAPPER,
            new SourceEventCodec(
                IngestorTestSupport.MAPPER,
                new SourceEventValidator(BaseValidator.newValidator())));
    return loader.loadEventSet(readResource("/fixtures/v1/" + resourceName));
  }

  private static String readResource(String resourcePath) {
    try (InputStream resource = ReportServiceTest.class.getResourceAsStream(resourcePath)) {
      if (resource == null) {
        throw new IllegalArgumentException("no fixture resource at " + resourcePath);
      }
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
