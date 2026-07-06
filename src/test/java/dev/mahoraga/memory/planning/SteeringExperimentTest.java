package dev.mahoraga.memory.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.boundary.BoundaryFactQuery;
import dev.mahoraga.memory.boundary.BoundaryPosition;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.boundary.KnowledgeBoundaryCodec;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.fixture.CandidateActionSet;
import dev.mahoraga.memory.fixture.FixtureEventSet;
import dev.mahoraga.memory.fixture.FixtureTestSupport;
import dev.mahoraga.memory.fixture.FixtureTestSupport.V1Bundle;
import dev.mahoraga.memory.fixture.PlannerCandidateSet;
import dev.mahoraga.memory.fixture.RunnerManifest;
import dev.mahoraga.memory.ingest.IngestorTestSupport;
import dev.mahoraga.memory.planning.SteeringArmEvidence.ArmMode;
import dev.mahoraga.memory.posture.SelectedFact;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Executes both experiment arms through the production runner against two
 * independent empty PostgreSQL databases and proves the complete steering
 * contract: equal finalized E1 state, planning before any E2 event, equal
 * controlled inputs, exactly the baseline and memory orders, persisted
 * execution, causative 3 -> 1 metrics, equal final facts and reports, and an
 * equality guard that rejects any tampered or partial arm.
 */
class SteeringExperimentTest {

  private static final String TENANT = "tenant-acme";
  private static final List<String> BASELINE_ORDER = List.of("T-A", "T-B", "T-C");
  private static final List<String> MEMORY_ORDER = List.of("T-C", "T-A", "T-B");
  private static final String REGRESSION_OCCURRENCE_EVENT_ID = "e2-regress";
  private static final SourceEventCodec CODEC =
      new SourceEventCodec(
          Jackson.newObjectMapper(), new SourceEventValidator(BaseValidator.newValidator()));

  private static V1Bundle bundle;
  private static PlannerCandidateSet candidates;
  private static CandidateActionSet actions;
  private static IngestorTestSupport controlDb;
  private static IngestorTestSupport memoryDb;
  private static SteeringArmEvidence control;
  private static SteeringArmEvidence memory;
  private static SteeringComparison comparison;

  @BeforeAll
  static void executeBothArms() throws SQLException {
    bundle = FixtureTestSupport.loadV1();
    candidates = PlannerCandidateSet.from(bundle.e2Planner().trustedContext(), bundle.manifest());
    actions = CandidateActionSet.from(bundle.manifest(), bundle.e2Planner());
    controlDb = IngestorTestSupport.forDatabase("steering_control");
    memoryDb = IngestorTestSupport.forDatabase("steering_memory");
    control = runArm(controlDb, ArmMode.CONTROL, actions);
    memory = runArm(memoryDb, ArmMode.MEMORY, actions);
    comparison = new SteeringEvidenceComparator().compare(control, memory);
  }

  @Test
  void returnedOrdersAreExactlyBaselineAndMemoryOrders() {
    assertEquals(BASELINE_ORDER, control.executedOrder());
    assertEquals(MEMORY_ORDER, memory.executedOrder());
    assertEquals(BASELINE_ORDER, comparison.controlExecutedOrder());
    assertEquals(MEMORY_ORDER, comparison.memoryExecutedOrder());
  }

  @Test
  void metricsDeriveAsThreeAndOneFromPersistedHistories() {
    assertEquals(3, control.actionsBeforeRegression());
    assertEquals(1, memory.actionsBeforeRegression());
    assertEquals(3, comparison.controlActionsBeforeRegression());
    assertEquals(1, comparison.memoryActionsBeforeRegression());
  }

  @Test
  void bothArmsStartFromEqualFinalizedE1StateAndPlanBeforeAnyE2Event() {
    assertEquals(control.e1SemanticDigest(), memory.e1SemanticDigest());
    assertTrue(control.hasZeroE2EventsAtPlanning());
    assertTrue(memory.hasZeroE2EventsAtPlanning());
    String expectedBoundaryHash =
        KnowledgeBoundaryCodec.hash(
            KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-e1", 7))));
    assertEquals(expectedBoundaryHash, control.plannerBoundaryHash());
    assertEquals(expectedBoundaryHash, memory.plannerBoundaryHash());
  }

  @Test
  void controlledInputsAreEqualAndOnlyMemoryFeaturesDiffer() {
    assertEquals(control.candidateInputDigest(), memory.candidateInputDigest());
    assertEquals(BASELINE_ORDER, control.candidateIds());
    assertEquals(BASELINE_ORDER, memory.candidateIds());
    assertEquals(control.candidateSourceEventIds(), memory.candidateSourceEventIds());
  }

  @Test
  void executedHistoriesArePersistedCompletelyInBothIsolatedDatabases() {
    for (IngestorTestSupport db : List.of(controlDb, memoryDb)) {
      control
          .candidateSourceEventIds()
          .values()
          .forEach(
              eventIds ->
                  eventIds.forEach(eventId -> assertTrue(db.eventExists(TENANT, eventId))));
      assertEquals(18, db.count("source_events", TENANT));
      assertEquals(6, db.count("findings", TENANT));
      assertEquals(8, db.count("finding_occurrences", TENANT));
      assertEquals(5, db.count("test_attempts", TENANT));
    }
  }

  @Test
  void finalSemanticFactsAndMemoryReportsAreEqualAcrossArms() {
    assertEquals(control.e2FactSetDigest(), memory.e2FactSetDigest());
    assertEquals(control.memoryReportDigest(), memory.memoryReportDigest());
    assertEquals(control.e2FactSetDigest(), comparison.e2FactSetDigest());
    assertEquals(control.memoryReportDigest(), comparison.memoryReportDigest());
  }

  @Test
  void causativeSourceEventLinkageDeterminesEachMetric() {
    for (SteeringArmEvidence evidence : List.of(control, memory)) {
      String causativeCandidateId =
          evidence.candidateSourceEventIds().entrySet().stream()
              .filter(entry -> entry.getValue().contains(REGRESSION_OCCURRENCE_EVENT_ID))
              .map(Map.Entry::getKey)
              .reduce((first, second) -> {
                throw new IllegalStateException("two candidates claim the regression event");
              })
              .orElseThrow();
      assertEquals(
          evidence.executedOrder().indexOf(causativeCandidateId) + 1,
          evidence.actionsBeforeRegression());
    }
  }

  @Test
  void metricComesFromRecordedLinkageNotFromACandidateLiteral() {
    TrustedContext e2Context = new TrustedContext(TENANT, "engagement-2");
    KnowledgeBoundary memoryBoundary =
        KnowledgeBoundary.of(
            List.of(new BoundaryPosition("stream-e1", 7), new BoundaryPosition("stream-e2", 9)));
    List<SelectedFact> facts =
        controlDb.jdbi.withHandle(
            handle -> new BoundaryFactQuery().selectFacts(handle, e2Context, memoryBoundary));
    // Moving the regression occurrence to another candidate's recorded events
    // moves the metric with it; removing it from every candidate fails loudly.
    Map<String, List<String>> relinked =
        Map.of(
            "T-A", List.of(REGRESSION_OCCURRENCE_EVENT_ID),
            "T-B", List.of("e2-fixed-neg"),
            "T-C", List.of("e2-still", "e2-still-detect"));
    assertEquals(
        1,
        CausativeRegressionMetric.actionsBeforeRegression(
            facts, "engagement-2", BASELINE_ORDER, relinked));
    Map<String, List<String>> unlinked =
        Map.of(
            "T-A", List.of("e2-still", "e2-still-detect"),
            "T-B", List.of("e2-fixed-neg"),
            "T-C", List.of("e2-regress-detect"));
    assertThrows(
        IllegalStateException.class,
        () ->
            CausativeRegressionMetric.actionsBeforeRegression(
                facts, "engagement-2", BASELINE_ORDER, unlinked));
  }

  @Test
  void backgroundEventsAreIdenticalControlledInputsAndNeverPlannerCandidates() {
    List<String> backgroundIds = bundle.e2Background().eventIds();
    List<String> executedActionIds =
        control.candidateSourceEventIds().values().stream().flatMap(List::stream).toList();
    assertTrue(Collections.disjoint(backgroundIds, executedActionIds));
    assertTrue(
        Collections.disjoint(
            backgroundIds, control.candidateIds()),
        "background events must not appear as planner candidates");
    List<String> supportingIds =
        actions.supportingEvents().stream().map(event -> event.event().sourceEventId()).toList();
    assertTrue(Collections.disjoint(supportingIds, executedActionIds));
  }

  @Test
  void reorderingManifestReferencesCannotFalsifyTheMetricOrTheGuard() throws SQLException {
    IngestorTestSupport reorderedDb = IngestorTestSupport.forDatabase("steering_reordered");
    SteeringArmEvidence reordered = runArm(reorderedDb, ArmMode.MEMORY, reversedActions(actions));
    assertEquals(MEMORY_ORDER, reordered.executedOrder());
    assertEquals(memory.actionsBeforeRegression(), reordered.actionsBeforeRegression());
    assertEquals(control.e2FactSetDigest(), reordered.e2FactSetDigest());
    SteeringComparison reorderedComparison =
        new SteeringEvidenceComparator().compare(control, reordered);
    assertEquals(1, reorderedComparison.memoryActionsBeforeRegression());
  }

  @Test
  void changingOneFrozenOutcomeFailsTheEqualityGuard() throws SQLException {
    IngestorTestSupport alteredDb = IngestorTestSupport.forDatabase("steering_altered");
    FixtureEventSet alteredPlannerEvents =
        withAlteredOutcome(bundle.e2Planner(), "e2-still-detect");
    CandidateActionSet alteredActions =
        CandidateActionSet.from(bundle.manifest(), alteredPlannerEvents);
    SteeringArmEvidence altered = runArm(alteredDb, ArmMode.MEMORY, alteredActions);
    assertNotEquals(control.candidateInputDigest(), altered.candidateInputDigest());
    assertThrows(
        IllegalArgumentException.class,
        () -> new SteeringEvidenceComparator().compare(control, altered));
  }

  @Test
  void removingOneFrozenOutcomeFailsTheArmWithoutEvidence() throws SQLException {
    IngestorTestSupport failedDb = IngestorTestSupport.forDatabase("steering_failed");
    Map<String, List<CanonicalSourceEvent>> pruned =
        new LinkedHashMap<>(actions.eventsByCandidateId());
    pruned.put(
        "T-C",
        actions.actionsFor("T-C").stream()
            .filter(event -> !event.event().sourceEventId().equals("e2-regress-detect"))
            .toList());
    CandidateActionSet prunedActions =
        new CandidateActionSet(actions.trustedContext(), pruned, actions.supportingEvents());
    // The declared completion marker never finalizes over the missing position,
    // so the arm fails before any evidence value exists.
    assertThrows(
        IllegalStateException.class, () -> runArm(failedDb, ArmMode.MEMORY, prunedActions));
  }

  @Test
  void anArmNeverResumesAUsedDatabase() {
    assertThrows(
        IllegalStateException.class, () -> runArm(controlDb, ArmMode.CONTROL, actions));
  }

  @Test
  void comparatorRejectsSameModeMissingOrUnequalEvidence() {
    SteeringEvidenceComparator comparator = new SteeringEvidenceComparator();
    assertThrows(IllegalArgumentException.class, () -> comparator.compare(control, control));
    assertThrows(IllegalArgumentException.class, () -> comparator.compare(memory, control));
    assertThrows(NullPointerException.class, () -> comparator.compare(control, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.compare(control, withUnplannedE2(memory)));
    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.compare(control, withE1Digest(memory, "0".repeat(64))));
    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.compare(control, withPartialExecution(memory)));
  }

  @Test
  void plannerInputCannotStructurallyAcceptOutcomeBearingTypes() {
    for (Class<?> plannerType :
        List.of(DeterministicPlanner.class, PreEngagementMemoryQuery.class)) {
      for (Method method : plannerType.getMethods()) {
        for (Class<?> parameterType : method.getParameterTypes()) {
          assertFalse(
              parameterType == RunnerManifest.class || parameterType == CandidateActionSet.class,
              "%s.%s accepts outcome-bearing type %s"
                  .formatted(plannerType.getSimpleName(), method.getName(), parameterType));
        }
      }
    }
    for (var component : PlannerRequest.class.getRecordComponents()) {
      assertFalse(
          component.getType() == RunnerManifest.class
              || component.getType() == CandidateActionSet.class);
    }
  }

  private static SteeringArmEvidence runArm(
      IngestorTestSupport db, ArmMode mode, CandidateActionSet armActions) {
    return new SteeringArmRunner(db.jdbi, db.ingestor)
        .execute(
            mode,
            bundle.e1(),
            candidates,
            armActions,
            bundle.e2Background(),
            bundle.e2Completion());
  }

  /** Reverses candidate iteration and per-candidate reference order only. */
  private static CandidateActionSet reversedActions(CandidateActionSet base) {
    List<String> candidateIds = new ArrayList<>(base.eventsByCandidateId().keySet());
    Collections.reverse(candidateIds);
    Map<String, List<CanonicalSourceEvent>> reversed = new LinkedHashMap<>();
    for (String candidateId : candidateIds) {
      reversed.put(candidateId, base.actionsFor(candidateId).reversed());
    }
    return new CandidateActionSet(base.trustedContext(), reversed, base.supportingEvents());
  }

  /** Re-decodes one event with its frozen result flipped to not_detected. */
  private static FixtureEventSet withAlteredOutcome(FixtureEventSet plannerEvents, String eventId) {
    List<CanonicalSourceEvent> events =
        plannerEvents.events().stream()
            .map(
                event ->
                    event.event().sourceEventId().equals(eventId)
                        ? CODEC.decode(
                            new String(event.canonicalJson(), StandardCharsets.UTF_8)
                                .replace("\"detected\"", "\"not_detected\""))
                        : event)
            .toList();
    return new FixtureEventSet(plannerEvents.trustedContext(), events);
  }

  private static SteeringArmEvidence withUnplannedE2(SteeringArmEvidence base) {
    return new SteeringArmEvidence(
        base.armMode(), base.candidateInputDigest(), base.candidateIds(), base.executedOrder(),
        base.candidateSourceEventIds(), base.actionsBeforeRegression(),
        base.plannerBoundaryHash(), false, base.e1SemanticDigest(), base.e2FactSetDigest(),
        base.memoryReportDigest());
  }

  private static SteeringArmEvidence withE1Digest(SteeringArmEvidence base, String e1Digest) {
    return new SteeringArmEvidence(
        base.armMode(), base.candidateInputDigest(), base.candidateIds(), base.executedOrder(),
        base.candidateSourceEventIds(), base.actionsBeforeRegression(),
        base.plannerBoundaryHash(), base.hasZeroE2EventsAtPlanning(), e1Digest,
        base.e2FactSetDigest(), base.memoryReportDigest());
  }

  /** Drops the last executed candidate, simulating a partially executed arm. */
  private static SteeringArmEvidence withPartialExecution(SteeringArmEvidence base) {
    List<String> partialOrder = base.executedOrder().subList(0, base.executedOrder().size() - 1);
    Map<String, List<String>> partialEvents = new LinkedHashMap<>();
    partialOrder.forEach(
        candidateId -> partialEvents.put(candidateId, base.candidateSourceEventIds().get(candidateId)));
    return new SteeringArmEvidence(
        base.armMode(), base.candidateInputDigest(), base.candidateIds(), partialOrder,
        partialEvents, 1, base.plannerBoundaryHash(), base.hasZeroE2EventsAtPlanning(),
        base.e1SemanticDigest(), base.e2FactSetDigest(), base.memoryReportDigest());
  }
}
