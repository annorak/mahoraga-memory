package dev.mahoraga.memory.fixture;

import static dev.mahoraga.memory.fixture.FixtureTestSupport.loadV1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.boundary.BoundaryFactQuery;
import dev.mahoraga.memory.boundary.BoundaryPosition;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.fixture.FixtureTestSupport.V1Bundle;
import dev.mahoraga.memory.ingest.IngestorTestSupport;
import dev.mahoraga.memory.posture.PostureFolder;
import dev.mahoraga.memory.posture.PostureResult;
import dev.mahoraga.memory.posture.PostureResult.EpisodeClassification;
import dev.mahoraga.memory.posture.PostureResult.LastVerifiedExposure;
import dev.mahoraga.memory.posture.SelectedFact;
import dev.mahoraga.memory.posture.SelectedFact.FindingOccurrence;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the v1 fixture bundle through production PostgreSQL ingestion: E1
 * finalizes before any E2 event, the two engagements finalize at their declared
 * boundaries, the memory boundary folds to exactly the six longitudinal
 * scenarios while the planner boundary sees zero E2 facts with REGRESS already
 * resolved at E1, Pod churn preserves one canonical Deployment, and the weak
 * collision is ambiguous with no posture effect.
 */
class SixScenarioIntegrationTest {

  private static final String DATABASE = "six_scenario";
  private static final String TENANT = "tenant-acme";
  private static final BoundaryFactQuery QUERY = new BoundaryFactQuery();
  private static final PostureFolder FOLDER = new PostureFolder();

  private static IngestorTestSupport db;
  private static V1Bundle bundle;
  private static Long e1BoundaryBeforeE2;
  private static int eventCountBeforeE2;

  @BeforeAll
  static void ingestBothEngagements() throws SQLException {
    db = IngestorTestSupport.forDatabase(DATABASE);
    bundle = loadV1();

    ingest(bundle.e1());
    // Capture that E1 is finalized and no E2 event exists yet, proving the
    // planner boundary is taken strictly before any E2 outcome is ingested.
    e1BoundaryBeforeE2 = db.boundary(TENANT, "engagement-1");
    eventCountBeforeE2 = db.count("source_events", TENANT);

    ingest(bundle.e2Planner());
    ingest(bundle.e2Background());
    ingest(bundle.e2Completion());
  }

  @Test
  void e1FinalizesBeforeAnyE2EventAndBothReachDeclaredBoundaries() {
    assertEquals(7L, e1BoundaryBeforeE2);
    assertEquals(8, eventCountBeforeE2);
    assertEquals(7L, db.boundary(TENANT, "engagement-1"));
    assertEquals(9L, db.boundary(TENANT, "engagement-2"));
  }

  @Test
  void memoryBoundaryFoldYieldsExactlySixScenarios() {
    Map<String, PostureResult> byFinding = foldPerFinding(memoryBoundary(), "engagement-2");

    assertEquals(6, byFinding.size());
    assertEquals(EpisodeClassification.STILL_OPEN, episode(byFinding, "check-login-sqli"));
    assertEquals(EpisodeClassification.VERIFIED_RESOLVED, episode(byFinding, "check-search-xss"));
    assertEquals(EpisodeClassification.REGRESSED, episode(byFinding, "check-admin-authz"));
    assertEquals(EpisodeClassification.NOT_RETESTED, episode(byFinding, "check-upload-rce"));
    assertEquals(EpisodeClassification.INCONCLUSIVE, episode(byFinding, "check-api-ssrf"));
    assertEquals(EpisodeClassification.NEW, episode(byFinding, "check-report-idor"));
  }

  @Test
  void plannerBoundaryHasZeroE2FactsAndRegressIsResolvedAtE1() {
    List<SelectedFact> facts = selectFacts(plannerBoundary());
    assertTrue(
        facts.stream().allMatch(fact -> fact.engagementId().equals("engagement-1")),
        "the planner boundary must not see any E2 fact");

    PostureResult regress =
        foldPerFinding(plannerBoundary(), "engagement-1").get("check-admin-authz");
    // Within E1 the episode is NEW (the first detection happened in E1), but the
    // last verified exposure the planner keys on is VERIFIED_RESOLVED: REGRESS
    // was detected and then verified-resolved before the planner boundary. That
    // exposure, not the episode, is what makes E2's re-detection a regression.
    assertEquals(LastVerifiedExposure.VERIFIED_RESOLVED, regress.lastVerifiedExposure());
    assertEquals(EpisodeClassification.NEW, regress.episodeClassification());
  }

  @Test
  void podChurnPreservesOneCanonicalDeployment() {
    assertEquals(1, db.count("assets", TENANT));
    assertEquals(3, db.count("asset_observations", TENANT));
  }

  @Test
  void weakCollisionIsAmbiguousWithNoPostureEffect() {
    assertEquals(1, ambiguousObservationCount());
    assertEquals(0, ambiguousObservationsWithAsset());
    // The ambiguous observation creates no asset, finding, occurrence, or
    // attempt, so the posture-fact counts are exactly the authored facts.
    assertEquals(6, db.count("findings", TENANT));
    assertEquals(8, db.count("finding_occurrences", TENANT));
    assertEquals(5, db.count("test_attempts", TENANT));
  }

  private static void ingest(FixtureEventSet eventSet) {
    TrustedContext context = eventSet.trustedContext();
    for (CanonicalSourceEvent event : eventSet.events()) {
      db.ingestor.ingest(context, event);
    }
  }

  private static KnowledgeBoundary memoryBoundary() {
    return KnowledgeBoundary.of(
        List.of(new BoundaryPosition("stream-e1", 7), new BoundaryPosition("stream-e2", 9)));
  }

  private static KnowledgeBoundary plannerBoundary() {
    return KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-e1", 7)));
  }

  private static List<SelectedFact> selectFacts(KnowledgeBoundary boundary) {
    TrustedContext context = new TrustedContext(TENANT, "engagement-2");
    return db.jdbi.withHandle(handle -> QUERY.selectFacts(handle, context, boundary));
  }

  /**
   * Folds each boundary-selected finding independently. Every finding's fold
   * receives all selected attempts; the pure fold drops attempts incompatible
   * with that finding's baseline, so distinct verification keys keep the folds
   * from cross-contaminating.
   */
  private static Map<String, PostureResult> foldPerFinding(
      KnowledgeBoundary boundary, String currentEngagementId) {
    List<SelectedFact> facts = selectFacts(boundary);
    List<SelectedFact> attempts =
        facts.stream().filter(SelectedFact.TestAttempt.class::isInstance).toList();
    Map<UUID, List<SelectedFact>> occurrencesByFinding = new LinkedHashMap<>();
    for (SelectedFact fact : facts) {
      if (fact instanceof FindingOccurrence occurrence) {
        occurrencesByFinding
            .computeIfAbsent(occurrence.findingId(), key -> new ArrayList<>())
            .add(fact);
      }
    }
    Map<String, PostureResult> byVerificationKey = new LinkedHashMap<>();
    for (List<SelectedFact> occurrences : occurrencesByFinding.values()) {
      List<SelectedFact> forFinding = new ArrayList<>(occurrences);
      forFinding.addAll(attempts);
      FindingOccurrence first = (FindingOccurrence) occurrences.get(0);
      byVerificationKey.put(first.verificationKey(), FOLDER.fold(currentEngagementId, forFinding));
    }
    return byVerificationKey;
  }

  private static EpisodeClassification episode(Map<String, PostureResult> byFinding, String key) {
    return byFinding.get(key).episodeClassification();
  }

  private static int ambiguousObservationCount() {
    return db.jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT count(*) FROM asset_observations WHERE tenant_id = :tenantId"
                        + " AND resolution_outcome = 'AMBIGUOUS'")
                .bind("tenantId", TENANT)
                .mapTo(Integer.class)
                .one());
  }

  private static int ambiguousObservationsWithAsset() {
    return db.jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT count(*) FROM asset_observations WHERE tenant_id = :tenantId"
                        + " AND resolution_outcome = 'AMBIGUOUS'"
                        + " AND canonical_asset_id IS NOT NULL")
                .bind("tenantId", TENANT)
                .mapTo(Integer.class)
                .one());
  }
}
