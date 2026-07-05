package dev.mahoraga.memory.fixture;

import static dev.mahoraga.memory.fixture.FixtureTestSupport.fixture;
import static dev.mahoraga.memory.fixture.FixtureTestSupport.loader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.fixture.PlannerCandidateSet.PlannerCandidate;
import dev.mahoraga.memory.fixture.RunnerManifest.RunnerCandidate;
import dev.mahoraga.memory.ingest.SourceEventIngestor;
import io.dropwizard.jackson.Jackson;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PlannerProjectionTest {

  private static final List<String> FORBIDDEN_TOKENS =
      List.of(
          "F-STILL", "F-FIXED", "F-REGRESS", "F-UNTESTED", "F-INCONCLUSIVE", "F-NEW", "detected");

  private final FixtureLoader loader = loader();

  @Test
  void projectionExposesOnlyPlannerSafeFields() {
    assertEquals(
        Set.of("tenantId", "actionBudget", "candidates"),
        componentNames(PlannerCandidateSet.class));
    assertEquals(
        Set.of("candidateId", "target", "verificationKey"), componentNames(PlannerCandidate.class));
    assertEquals(
        Set.of("clusterId", "resourceKind", "resourceUid"), componentNames(DeploymentTarget.class));
  }

  @Test
  void projectionCarriesTenantBudgetCandidatesAndTargets() {
    PlannerCandidateSet projection = project();
    assertEquals("tenant-demo", projection.tenantId());
    assertEquals(3, projection.actionBudget());
    assertEquals(
        List.of("T-A", "T-B", "T-C"),
        projection.candidates().stream().map(PlannerCandidate::candidateId).toList());
    PlannerCandidate first = projection.candidates().get(0);
    assertEquals("vk-alpha", first.verificationKey());
    assertEquals("uid-a", first.target().resourceUid());
  }

  @Test
  void projectionSerializationCarriesNoRunnerLeakage() throws Exception {
    ObjectMapper mapper = Jackson.newObjectMapper();
    String serialized = mapper.writeValueAsString(project());
    for (String token : FORBIDDEN_TOKENS) {
      assertFalse(serialized.contains(token), "projection leaked token: " + token);
    }
    assertTrue(serialized.contains("T-A"), serialized);
    assertTrue(serialized.contains("vk-alpha"), serialized);
  }

  @Test
  void manifestExposesLabelsAndOutcomesButProjectionDoesNot() {
    RunnerManifest manifest = loadManifest();
    RunnerCandidate first = manifest.candidates().get(0);
    assertEquals("F-STILL", first.scenarioLabel());
    assertEquals(ExecutionStatus.COMPLETED, first.frozenOutcome().executionStatus());
    assertEquals(TestResult.DETECTED, first.frozenOutcome().result());
    assertEquals(
        List.of("F-NEW", "F-INCONCLUSIVE"),
        manifest.background().stream()
            .map(RunnerManifest.BackgroundEvent::scenarioLabel)
            .toList());

    Set<String> plannerFields = componentNames(PlannerCandidate.class);
    assertFalse(plannerFields.contains("scenarioLabel"));
    assertFalse(plannerFields.contains("frozenOutcome"));
  }

  @Test
  void ingestionSignatureCannotAcceptFixtureTypes() {
    for (Method method : SourceEventIngestor.class.getMethods()) {
      for (Class<?> parameterType : method.getParameterTypes()) {
        assertFalse(
            "dev.mahoraga.memory.fixture".equals(parameterType.getPackageName()),
            "ingestion method " + method.getName() + " accepts fixture type " + parameterType);
      }
    }
  }

  private PlannerCandidateSet project() {
    FixtureEventSet eventSet = loader.loadEventSet(fixture("event-set.json"));
    RunnerManifest manifest = loader.loadManifest(fixture("manifest.json"), eventSet);
    return PlannerCandidateSet.from(eventSet.trustedContext(), manifest);
  }

  private RunnerManifest loadManifest() {
    FixtureEventSet eventSet = loader.loadEventSet(fixture("event-set.json"));
    return loader.loadManifest(fixture("manifest.json"), eventSet);
  }

  private static Set<String> componentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toSet());
  }
}
