package dev.mahoraga.memory.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mahoraga.memory.boundary.BoundaryPosition;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.fixture.DeploymentTarget;
import dev.mahoraga.memory.fixture.FixtureEventSet;
import dev.mahoraga.memory.fixture.FixtureLoader;
import dev.mahoraga.memory.fixture.FixtureTestSupport;
import dev.mahoraga.memory.fixture.FixtureTestSupport.V1Bundle;
import dev.mahoraga.memory.fixture.PlannerCandidateSet;
import dev.mahoraga.memory.ingest.IngestorTestSupport;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves the memory query over real persisted PostgreSQL facts: features are
 * derived from finalized E1 history at an explicit boundary, ingested E2 facts
 * beyond that boundary change nothing, the derived features flip the plan from
 * the baseline order to the memory order, and neither another Deployment nor
 * another tenant with the same verification key can contribute.
 */
class PreEngagementMemoryQueryTest {

  private static final String DATABASE = "planner_memory";
  private static final String TENANT = "tenant-acme";
  private static final TrustedContext PLANNING_CONTEXT =
      new TrustedContext(TENANT, "engagement-2");
  private static final KnowledgeBoundary E1_BOUNDARY =
      KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-e1", 7)));
  private static final KnowledgeBoundary MEMORY_BOUNDARY =
      KnowledgeBoundary.of(
          List.of(new BoundaryPosition("stream-e1", 7), new BoundaryPosition("stream-e2", 9)));

  private static IngestorTestSupport db;
  private static List<CandidateTest> candidates;
  private static int actionBudget;

  private final PreEngagementMemoryQuery query = new PreEngagementMemoryQuery();
  private final DeterministicPlanner planner = new DeterministicPlanner();

  @BeforeAll
  static void ingestAllHistories() throws SQLException {
    db = IngestorTestSupport.forDatabase(DATABASE);
    V1Bundle bundle = FixtureTestSupport.loadV1();
    ingest(bundle.e1());
    ingest(bundle.e2Planner());
    ingest(bundle.e2Background());
    ingest(bundle.e2Completion());
    FixtureLoader loader = FixtureTestSupport.loader();
    ingest(loader.loadEventSet(SECOND_DEPLOYMENT_HISTORY));
    ingest(loader.loadEventSet(OTHER_TENANT_HISTORY));
    PlannerCandidateSet projection =
        PlannerCandidateSet.from(bundle.e2Planner().trustedContext(), bundle.manifest());
    candidates =
        projection.candidates().stream()
            .map(
                candidate ->
                    new CandidateTest(
                        candidate.candidateId(), candidate.target(), candidate.verificationKey()))
            .toList();
    actionBudget = projection.actionBudget();
  }

  @Test
  void featuresAtThePlannerBoundaryComeFromFinalizedE1Facts() {
    assertEquals(
        List.of(
            new MemoryFeature("T-A", false),
            new MemoryFeature("T-B", false),
            new MemoryFeature("T-C", true)),
        derive(PLANNING_CONTEXT, E1_BOUNDARY, candidates));
  }

  @Test
  void derivedFeaturesFlipThePlanFromBaselineToMemoryOrder() {
    Plan memoryOff =
        planner.plan(
            new PlannerRequest(TENANT, candidates, actionBudget, E1_BOUNDARY, List.of()));
    Plan memoryOn =
        planner.plan(
            new PlannerRequest(
                TENANT,
                candidates,
                actionBudget,
                E1_BOUNDARY,
                derive(PLANNING_CONTEXT, E1_BOUNDARY, candidates)));
    assertEquals(List.of("T-A", "T-B", "T-C"), memoryOff.orderedCandidateIds());
    assertEquals(List.of("T-C", "T-A", "T-B"), memoryOn.orderedCandidateIds());
  }

  @Test
  void ingestedE2FactsBeyondTheBoundaryCannotAffectFeaturesOrPlans() {
    // Both engagements are fully ingested and finalized, and their streams use
    // overlapping sequence numbers; the E1-only boundary still derives the same
    // features on every retry, so persisted future facts cannot leak backward.
    List<MemoryFeature> first = derive(PLANNING_CONTEXT, E1_BOUNDARY, candidates);
    List<MemoryFeature> retried = derive(PLANNING_CONTEXT, E1_BOUNDARY, candidates);
    assertEquals(first, retried);
    assertEquals(new MemoryFeature("T-C", true), first.get(2));
    List<CandidateTest> reversed = List.copyOf(candidates.reversed());
    Plan reversedArrival =
        planner.plan(
            new PlannerRequest(
                TENANT,
                reversed,
                actionBudget,
                E1_BOUNDARY,
                derive(PLANNING_CONTEXT, E1_BOUNDARY, reversed)));
    assertEquals(List.of("T-C", "T-A", "T-B"), reversedArrival.orderedCandidateIds());
  }

  @Test
  void anInBoundLaterDetectionMakesPriorResolutionFalse() {
    // At the E1+E2 boundary REGRESS's old negative is followed by a visible
    // detection (false), while FIXED's compatible completed negative holds.
    assertEquals(
        List.of(
            new MemoryFeature("T-A", false),
            new MemoryFeature("T-B", true),
            new MemoryFeature("T-C", false)),
        derive(PLANNING_CONTEXT, MEMORY_BOUNDARY, candidates));
  }

  @Test
  void incompleteOrAbsentAttemptsNeverCreateVerifiedResolution() {
    List<MemoryFeature> features =
        derive(
            PLANNING_CONTEXT,
            MEMORY_BOUNDARY,
            List.of(
                candidate("T-partial", webTarget(), "check-api-ssrf"),
                candidate("T-untested", webTarget(), "check-upload-rce")));
    assertEquals(
        List.of(
            new MemoryFeature("T-partial", false), new MemoryFeature("T-untested", false)),
        features);
  }

  @Test
  void unknownVerificationKeyOrTargetIsFalseNotAnError() {
    List<MemoryFeature> features =
        derive(
            PLANNING_CONTEXT,
            E1_BOUNDARY,
            List.of(
                candidate("T-key", webTarget(), "check-never-seen"),
                candidate(
                    "T-ghost",
                    new DeploymentTarget("cluster-prod", "Deployment", "deploy-ghost"),
                    "check-admin-authz")));
    assertEquals(
        List.of(new MemoryFeature("T-key", false), new MemoryFeature("T-ghost", false)),
        features);
  }

  @Test
  void sameVerificationKeyOnAnotherDeploymentCannotContaminate() {
    KnowledgeBoundary withSecondDeployment =
        KnowledgeBoundary.of(
            List.of(new BoundaryPosition("stream-e1", 7), new BoundaryPosition("stream-e3", 2)));
    List<MemoryFeature> features =
        derive(
            PLANNING_CONTEXT,
            withSecondDeployment,
            List.of(
                candidate("T-web", webTarget(), "check-admin-authz"),
                candidate(
                    "T-api",
                    new DeploymentTarget("cluster-prod", "Deployment", "deploy-api"),
                    "check-admin-authz")));
    assertEquals(
        List.of(new MemoryFeature("T-web", true), new MemoryFeature("T-api", false)), features);
  }

  @Test
  void anotherTenantsHistoryCannotAffectFeatures() {
    TrustedContext otherTenant = new TrustedContext("tenant-beta", "engagement-b1");
    List<MemoryFeature> features =
        derive(
            otherTenant,
            KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-b1", 2))),
            List.of(candidate("T-web", webTarget(), "check-admin-authz")));
    assertEquals(List.of(new MemoryFeature("T-web", false)), features);
    // A boundary naming another tenant's stream fails closed instead of
    // silently returning that tenant's facts.
    assertThrows(
        IllegalArgumentException.class, () -> derive(otherTenant, E1_BOUNDARY, candidates));
  }

  @Test
  void nonFinalizedOrUnknownBoundaryPositionsReject() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            derive(
                PLANNING_CONTEXT,
                KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-e1", 5))),
                candidates));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            derive(
                PLANNING_CONTEXT,
                KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-nope", 1))),
                candidates));
  }

  @Test
  void duplicateCandidateIdsRejectBeforeAnyQuery() {
    List<CandidateTest> duplicated =
        List.of(
            candidate("T-A", webTarget(), "check-login-sqli"),
            candidate("T-A", webTarget(), "check-search-xss"));
    assertThrows(
        IllegalArgumentException.class, () -> derive(PLANNING_CONTEXT, E1_BOUNDARY, duplicated));
  }

  private List<MemoryFeature> derive(
      TrustedContext context, KnowledgeBoundary boundary, List<CandidateTest> requested) {
    return db.jdbi.withHandle(
        handle -> query.deriveFeatures(handle, context, boundary, requested));
  }

  private static CandidateTest candidate(
      String candidateId, DeploymentTarget target, String verificationKey) {
    return new CandidateTest(candidateId, target, verificationKey);
  }

  private static DeploymentTarget webTarget() {
    return new DeploymentTarget("cluster-prod", "Deployment", "deploy-web");
  }

  private static void ingest(FixtureEventSet eventSet) {
    TrustedContext context = eventSet.trustedContext();
    for (CanonicalSourceEvent event : eventSet.events()) {
      db.ingestor.ingest(context, event);
    }
  }

  /**
   * A finalized second Deployment of the same tenant whose open finding shares
   * REGRESS's verification key. This history exposes cross-asset contamination.
   */
  private static final String SECOND_DEPLOYMENT_HISTORY =
      """
      {
        "trusted_context": {"tenant_id": "tenant-acme", "engagement_id": "engagement-3"},
        "events": [
          {
            "source_event_id": "e3-asset",
            "event_type": "asset_observation",
            "source_stream_id": "stream-e3",
            "source_sequence": 1,
            "schema_version": 1,
            "occurred_at": "2026-03-02T09:00:00Z",
            "payload": {
              "cluster_id": "cluster-prod",
              "resource_kind": "Deployment",
              "resource_uid": "deploy-api",
              "pod_uid": "pod-e3"
            }
          },
          {
            "source_event_id": "e3-authz",
            "event_type": "finding_observation",
            "source_stream_id": "stream-e3",
            "source_sequence": 2,
            "schema_version": 1,
            "occurred_at": "2026-03-02T09:01:00Z",
            "payload": {
              "cluster_id": "cluster-prod",
              "resource_kind": "Deployment",
              "resource_uid": "deploy-api",
              "vuln_class": "broken_authz",
              "normalized_location_signature": "sig-api-admin-authz",
              "verification_key": "check-admin-authz",
              "check_version": "1.0.0",
              "relevant_context": {
                "protocol": "https",
                "port": 443,
                "normalized_route": "/admin",
                "is_address_bound": false
              },
              "compatibility_policy_version": 1
            }
          },
          {
            "source_event_id": "e3-marker",
            "event_type": "engagement_completed",
            "source_stream_id": "stream-e3",
            "source_sequence": 3,
            "schema_version": 1,
            "occurred_at": "2026-03-02T09:02:00Z",
            "payload": {"last_data_sequence": 2}
          }
        ]
      }
      """;

  /**
   * A finalized parallel tenant with the same authoritative Deployment key and
   * verification key but no verified resolution. This history exposes tenant
   * leakage through either identity dimension.
   */
  private static final String OTHER_TENANT_HISTORY =
      """
      {
        "trusted_context": {"tenant_id": "tenant-beta", "engagement_id": "engagement-b1"},
        "events": [
          {
            "source_event_id": "b1-asset",
            "event_type": "asset_observation",
            "source_stream_id": "stream-b1",
            "source_sequence": 1,
            "schema_version": 1,
            "occurred_at": "2026-03-01T09:00:00Z",
            "payload": {
              "cluster_id": "cluster-prod",
              "resource_kind": "Deployment",
              "resource_uid": "deploy-web",
              "pod_uid": "pod-b1"
            }
          },
          {
            "source_event_id": "b1-authz",
            "event_type": "finding_observation",
            "source_stream_id": "stream-b1",
            "source_sequence": 2,
            "schema_version": 1,
            "occurred_at": "2026-03-01T09:01:00Z",
            "payload": {
              "cluster_id": "cluster-prod",
              "resource_kind": "Deployment",
              "resource_uid": "deploy-web",
              "vuln_class": "broken_authz",
              "normalized_location_signature": "sig-admin-authz",
              "verification_key": "check-admin-authz",
              "check_version": "1.0.0",
              "relevant_context": {
                "protocol": "https",
                "port": 443,
                "normalized_route": "/admin",
                "is_address_bound": false
              },
              "compatibility_policy_version": 1
            }
          },
          {
            "source_event_id": "b1-marker",
            "event_type": "engagement_completed",
            "source_stream_id": "stream-b1",
            "source_sequence": 3,
            "schema_version": 1,
            "occurred_at": "2026-03-01T09:02:00Z",
            "payload": {"last_data_sequence": 2}
          }
        ]
      }
      """;
}
