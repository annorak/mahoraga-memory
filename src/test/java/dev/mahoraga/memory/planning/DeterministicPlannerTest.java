package dev.mahoraga.memory.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.boundary.BoundaryPosition;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.fixture.DeploymentTarget;
import io.dropwizard.jackson.Jackson;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The two fixed ordering rules over validated planner input. Memory off and
 * memory on differ only by supplied features; both are exact, deterministic,
 * and budget-bounded, and the request type structurally cannot carry runner
 * labels, frozen outcomes, or an expected order.
 */
class DeterministicPlannerTest {

  private static final KnowledgeBoundary BOUNDARY =
      KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-e1", 7)));
  private static final List<String> FORBIDDEN_COMPONENT_FRAGMENTS =
      List.of("scenario", "label", "outcome", "frozen", "result", "classification", "expected");

  private final DeterministicPlanner planner = new DeterministicPlanner();

  @Test
  void memoryOffOrdersByCandidateIdAscending() {
    PlannerRequest request =
        request(List.of(candidate("T-B"), candidate("T-C"), candidate("T-A")), 3, List.of());
    assertEquals(List.of("T-A", "T-B", "T-C"), planner.plan(request).orderedCandidateIds());
  }

  @Test
  void memoryOnOrdersPriorResolutionFirstThenCandidateId() {
    PlannerRequest request =
        request(
            List.of(candidate("T-A"), candidate("T-B"), candidate("T-C")),
            3,
            List.of(feature("T-A", false), feature("T-B", false), feature("T-C", true)));
    assertEquals(List.of("T-C", "T-A", "T-B"), planner.plan(request).orderedCandidateIds());
  }

  @Test
  void arrivalOrderOfCandidatesAndFeaturesNeverChangesThePlan() {
    PlannerRequest shuffled =
        request(
            List.of(candidate("T-C"), candidate("T-A"), candidate("T-B")),
            3,
            List.of(feature("T-B", false), feature("T-C", true), feature("T-A", false)));
    assertEquals(List.of("T-C", "T-A", "T-B"), planner.plan(shuffled).orderedCandidateIds());
    // Retrying the identical request reproduces the identical ordered ids.
    assertEquals(planner.plan(shuffled), planner.plan(shuffled));
  }

  @Test
  void allFalseFeaturesMatchTheBaselineOrdering() {
    PlannerRequest memoryOn =
        request(
            List.of(candidate("T-B"), candidate("T-A"), candidate("T-C")),
            3,
            List.of(feature("T-A", false), feature("T-B", false), feature("T-C", false)));
    assertEquals(List.of("T-A", "T-B", "T-C"), planner.plan(memoryOn).orderedCandidateIds());
  }

  @Test
  void equalTrueFeaturesTieBreakByCandidateId() {
    PlannerRequest memoryOn =
        request(
            List.of(candidate("T-C"), candidate("T-B"), candidate("T-A")),
            3,
            List.of(feature("T-A", true), feature("T-B", true), feature("T-C", true)));
    assertEquals(List.of("T-A", "T-B", "T-C"), planner.plan(memoryOn).orderedCandidateIds());
  }

  @Test
  void budgetBoundsThePlanExactly() {
    List<CandidateTest> candidates =
        List.of(candidate("T-A"), candidate("T-B"), candidate("T-C"));
    List<MemoryFeature> features =
        List.of(feature("T-A", false), feature("T-B", false), feature("T-C", true));
    assertEquals(
        List.of("T-C"), planner.plan(request(candidates, 1, features)).orderedCandidateIds());
    assertEquals(
        List.of("T-C", "T-A"),
        planner.plan(request(candidates, 2, features)).orderedCandidateIds());
    assertEquals(
        List.of("T-C", "T-A", "T-B"),
        planner.plan(request(candidates, 3, features)).orderedCandidateIds());
    // A budget above the candidate count returns every candidate exactly once.
    assertEquals(
        List.of("T-C", "T-A", "T-B"),
        planner.plan(request(candidates, 7, features)).orderedCandidateIds());
    assertEquals(
        List.of("T-A", "T-B"), planner.plan(request(candidates, 2, List.of())).orderedCandidateIds());
  }

  @Test
  void nonPositiveBudgetsReject() {
    List<CandidateTest> candidates = List.of(candidate("T-A"));
    assertThrows(IllegalArgumentException.class, () -> request(candidates, 0, List.of()));
    assertThrows(IllegalArgumentException.class, () -> request(candidates, -1, List.of()));
  }

  @Test
  void duplicateCandidateIdsReject() {
    assertThrows(
        IllegalArgumentException.class,
        () -> request(List.of(candidate("T-A"), candidate("T-A")), 2, List.of()));
  }

  @Test
  void memoryOnFeatureCoverageMustBeExactlyOneToOne() {
    List<CandidateTest> candidates = List.of(candidate("T-A"), candidate("T-B"));
    assertThrows(
        IllegalArgumentException.class,
        () -> request(candidates, 2, List.of(feature("T-A", true))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                candidates,
                2,
                List.of(feature("T-A", true), feature("T-A", false), feature("T-B", true))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                candidates,
                2,
                List.of(feature("T-A", true), feature("T-B", true), feature("T-X", true))));
  }

  @Test
  void requiredInputsReject() {
    List<CandidateTest> candidates = List.of(candidate("T-A"));
    assertThrows(
        NullPointerException.class,
        () -> new PlannerRequest("tenant-acme", null, 1, BOUNDARY, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new PlannerRequest("tenant-acme", candidates, 1, null, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new PlannerRequest("tenant-acme", candidates, 1, BOUNDARY, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlannerRequest(" ", candidates, 1, BOUNDARY, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CandidateTest(" ", target(), "check-a"));
    assertThrows(NullPointerException.class, () -> new CandidateTest("T-A", null, "check-a"));
    assertThrows(IllegalArgumentException.class, () -> new CandidateTest("T-A", target(), " "));
    assertThrows(IllegalArgumentException.class, () -> new MemoryFeature(" ", true));
    assertThrows(
        IllegalArgumentException.class, () -> new Plan(List.of("T-A", "T-A")));
  }

  @Test
  void plannerInputExposesNoForbiddenFieldByReflection() {
    assertEquals(
        Set.of("tenantId", "candidates", "actionBudget", "knowledgeBoundary", "memoryFeatures"),
        componentNames(PlannerRequest.class));
    assertEquals(
        Set.of("candidateId", "target", "verificationKey"), componentNames(CandidateTest.class));
    assertEquals(
        Set.of("candidateId", "hasPriorVerifiedResolution"), componentNames(MemoryFeature.class));
    assertEquals(
        Set.of("clusterId", "resourceKind", "resourceUid"), componentNames(DeploymentTarget.class));
    for (Class<?> recordType :
        List.of(
            PlannerRequest.class,
            CandidateTest.class,
            MemoryFeature.class,
            DeploymentTarget.class,
            KnowledgeBoundary.class,
            BoundaryPosition.class)) {
      for (String componentName : componentNames(recordType)) {
        for (String forbidden : FORBIDDEN_COMPONENT_FRAGMENTS) {
          assertFalse(
              componentName.toLowerCase().contains(forbidden),
              recordType.getSimpleName() + " leaks forbidden component " + componentName);
        }
      }
    }
  }

  @Test
  void plannerInputSerializationCarriesNoRunnerVocabulary() throws Exception {
    ObjectMapper mapper = Jackson.newObjectMapper();
    PlannerRequest request =
        request(
            List.of(candidate("T-A"), candidate("T-B"), candidate("T-C")),
            3,
            List.of(feature("T-A", false), feature("T-B", false), feature("T-C", true)));
    String serialized = mapper.writeValueAsString(request);
    for (String token :
        List.of("F-STILL", "F-FIXED", "F-REGRESS", "F-UNTESTED", "F-INCONCLUSIVE", "F-NEW",
            "scenario", "frozen", "outcome", "detected")) {
      assertFalse(serialized.contains(token), "planner request leaked token: " + token);
    }
  }

  private static PlannerRequest request(
      List<CandidateTest> candidates, int actionBudget, List<MemoryFeature> memoryFeatures) {
    return new PlannerRequest("tenant-acme", candidates, actionBudget, BOUNDARY, memoryFeatures);
  }

  private static CandidateTest candidate(String candidateId) {
    return new CandidateTest(candidateId, target(), "check-" + candidateId);
  }

  private static MemoryFeature feature(String candidateId, boolean hasPriorVerifiedResolution) {
    return new MemoryFeature(candidateId, hasPriorVerifiedResolution);
  }

  private static DeploymentTarget target() {
    return new DeploymentTarget("cluster-prod", "Deployment", "deploy-web");
  }

  private static Set<String> componentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toSet());
  }
}
