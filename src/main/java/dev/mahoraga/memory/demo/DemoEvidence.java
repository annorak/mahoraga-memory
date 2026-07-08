package dev.mahoraga.memory.demo;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import dev.mahoraga.memory.demo.DemoArmEvidence.AmbiguityProof;
import dev.mahoraga.memory.demo.DemoArmEvidence.CompletionProbeResult;
import dev.mahoraga.memory.demo.DemoArmEvidence.ConflictProbeResult;
import dev.mahoraga.memory.demo.DemoArmEvidence.MemoryReportSummary;
import dev.mahoraga.memory.demo.DemoArmEvidence.StableIdentityProof;
import dev.mahoraga.memory.demo.DemoArmEvidence.StatelessReportSummary;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.planning.SteeringArmEvidence.ArmMode;
import dev.mahoraga.memory.planning.SteeringComparison;
import dev.mahoraga.memory.planning.SteeringEvidenceComparator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Normalized evidence produced from two isolated demo-arm files. */
@JsonPropertyOrder({
  "evidence_schema_version", "build_fingerprint", "candidate_ids",
  "control_executed_order", "memory_executed_order",
  "control_actions_before_regression", "memory_actions_before_regression",
  "planner_boundary_hash", "has_zero_e2_events_at_planning", "e1_semantic_digest",
  "candidate_input_digest", "e2_fact_set_digest", "stateless_report_digest",
  "memory_report_digest", "stable_identity_proof", "ambiguity_proof",
  "stateless_report_summary", "memory_report_summary", "duplicate_retry_result",
  "conflicting_duplicate_result", "missing_completion_result", "shuffle_digest_equality",
  "transaction_failure_leaves_partial_state"
})
public record DemoEvidence(
    int evidenceSchemaVersion,
    String buildFingerprint,
    List<String> candidateIds,
    List<String> controlExecutedOrder,
    List<String> memoryExecutedOrder,
    int controlActionsBeforeRegression,
    int memoryActionsBeforeRegression,
    String plannerBoundaryHash,
    boolean hasZeroE2EventsAtPlanning,
    String e1SemanticDigest,
    String candidateInputDigest,
    String e2FactSetDigest,
    String statelessReportDigest,
    String memoryReportDigest,
    StableIdentityProof stableIdentityProof,
    AmbiguityProof ambiguityProof,
    StatelessReportSummary statelessReportSummary,
    MemoryReportSummary memoryReportSummary,
    IngestResult duplicateRetryResult,
    ConflictProbeResult conflictingDuplicateResult,
    CompletionProbeResult missingCompletionResult,
    boolean shuffleDigestEquality,
    boolean transactionFailureLeavesPartialState) {

  private static final List<String> EXPECTED_CANDIDATES = List.of("T-A", "T-B", "T-C");
  private static final List<String> EXPECTED_MEMORY_ORDER = List.of("T-C", "T-A", "T-B");
  private static final StableIdentityProof EXPECTED_IDENTITY =
      new StableIdentityProof(true, true);
  private static final AmbiguityProof EXPECTED_AMBIGUITY = new AmbiguityProof("AMBIGUOUS", 0);
  private static final StatelessReportSummary EXPECTED_STATELESS =
      new StatelessReportSummary(3, 1, 1);
  private static final MemoryReportSummary EXPECTED_MEMORY =
      new MemoryReportSummary(1, 1, 1, 1, 1, 1);

  public DemoEvidence {
    if (evidenceSchemaVersion != DemoArmEvidence.SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported demo evidence schema version");
    }
    DemoArmEvidence.requireDigest(buildFingerprint, "buildFingerprint");
    candidateIds = copyUniqueNonempty(candidateIds, "candidateIds");
    controlExecutedOrder = copyUniqueNonempty(controlExecutedOrder, "controlExecutedOrder");
    memoryExecutedOrder = copyUniqueNonempty(memoryExecutedOrder, "memoryExecutedOrder");
    requireCompleteOrder(candidateIds, controlExecutedOrder, "controlExecutedOrder");
    requireCompleteOrder(candidateIds, memoryExecutedOrder, "memoryExecutedOrder");
    requirePosition(controlActionsBeforeRegression, controlExecutedOrder, "control metric");
    requirePosition(memoryActionsBeforeRegression, memoryExecutedOrder, "memory metric");
    DemoArmEvidence.requireDigest(plannerBoundaryHash, "plannerBoundaryHash");
    DemoArmEvidence.requireDigest(e1SemanticDigest, "e1SemanticDigest");
    DemoArmEvidence.requireDigest(candidateInputDigest, "candidateInputDigest");
    DemoArmEvidence.requireDigest(e2FactSetDigest, "e2FactSetDigest");
    DemoArmEvidence.requireDigest(statelessReportDigest, "statelessReportDigest");
    DemoArmEvidence.requireDigest(memoryReportDigest, "memoryReportDigest");
    Objects.requireNonNull(stableIdentityProof, "stableIdentityProof");
    Objects.requireNonNull(ambiguityProof, "ambiguityProof");
    Objects.requireNonNull(statelessReportSummary, "statelessReportSummary");
    Objects.requireNonNull(memoryReportSummary, "memoryReportSummary");
    Objects.requireNonNull(duplicateRetryResult, "duplicateRetryResult");
    Objects.requireNonNull(conflictingDuplicateResult, "conflictingDuplicateResult");
    Objects.requireNonNull(missingCompletionResult, "missingCompletionResult");
  }

  public static DemoEvidence compare(
      DemoArmEvidence control, DemoArmEvidence memory, String currentBuildFingerprint) {
    SteeringComparison comparison =
        new SteeringEvidenceComparator()
            .compare(control.steeringEvidence(), memory.steeringEvidence());
    requireExpectedArm(control);
    requireExpectedArm(memory);
    DemoArmEvidence.requireDigest(currentBuildFingerprint, "currentBuildFingerprint");
    requireEqual("control build", currentBuildFingerprint, control.buildFingerprint());
    requireEqual("memory build", currentBuildFingerprint, memory.buildFingerprint());
    requireEqualProofs(control, memory);
    requireCandidateEventEquality(control, memory);
    DemoEvidence evidence = combinedEvidence(control, comparison);
    requireExpectedEvidence(evidence);
    return evidence;
  }

  private static DemoEvidence combinedEvidence(
      DemoArmEvidence control, SteeringComparison comparison) {
    return new DemoEvidence(
        DemoArmEvidence.SCHEMA_VERSION,
        control.buildFingerprint(),
        control.candidateIds(),
        comparison.controlExecutedOrder(),
        comparison.memoryExecutedOrder(),
        comparison.controlActionsBeforeRegression(),
        comparison.memoryActionsBeforeRegression(),
        control.plannerBoundaryHash(),
        control.hasZeroE2EventsAtPlanning(),
        comparison.e1SemanticDigest(),
        comparison.candidateInputDigest(),
        comparison.e2FactSetDigest(),
        control.statelessReportDigest(),
        comparison.memoryReportDigest(),
        control.stableIdentityProof(),
        control.ambiguityProof(),
        control.statelessReportSummary(),
        control.memoryReportSummary(),
        control.duplicateRetryResult(),
        control.conflictingDuplicateResult(),
        control.missingCompletionResult(),
        control.shuffleDigestEquality(),
        control.rollbackProbeHasPartialState());
  }

  static void requireExpectedArm(DemoArmEvidence arm) {
    Objects.requireNonNull(arm, "arm");
    requireEqual("candidate ids", EXPECTED_CANDIDATES, arm.candidateIds());
    requireCompleteOrder(arm.candidateIds(), arm.executedOrder(), "executedOrder");
    List<String> expectedOrder =
        arm.armMode() == ArmMode.CONTROL ? EXPECTED_CANDIDATES : EXPECTED_MEMORY_ORDER;
    int expectedMetric = arm.armMode() == ArmMode.CONTROL ? 3 : 1;
    requireEqual("executed order", expectedOrder, arm.executedOrder());
    requireEqual("actions before regression", expectedMetric, arm.actionsBeforeRegression());
    if (!arm.hasZeroE2EventsAtPlanning()) {
      throw new IllegalArgumentException("demo arm planned after E2 data existed");
    }
    requireExpectedArmProofs(arm);
  }

  private static void requireExpectedArmProofs(DemoArmEvidence arm) {
    requireEqual("stable identity proof", EXPECTED_IDENTITY, arm.stableIdentityProof());
    requireEqual("ambiguity proof", EXPECTED_AMBIGUITY, arm.ambiguityProof());
    requireEqual("stateless summary", EXPECTED_STATELESS, arm.statelessReportSummary());
    requireEqual("memory summary", EXPECTED_MEMORY, arm.memoryReportSummary());
    requireEqual("duplicate retry", IngestResult.NO_OP, arm.duplicateRetryResult());
    requireEqual(
        "conflicting duplicate",
        ConflictProbeResult.EVENT_CONTENT_REJECTED,
        arm.conflictingDuplicateResult());
    requireEqual(
        "missing completion",
        CompletionProbeResult.UNFINALIZED_REPORT_BLOCKED,
        arm.missingCompletionResult());
    if (!arm.shuffleDigestEquality() || arm.rollbackProbeHasPartialState()) {
      throw new IllegalArgumentException("demo arm reproducibility or rollback proof failed");
    }
  }

  public static DemoArmEvidence readArm(Path input) throws IOException {
    return DemoArmEvidence.EvidenceJson.read(input, DemoArmEvidence.class);
  }

  public static DemoEvidence read(Path input) throws IOException {
    DemoEvidence evidence = DemoArmEvidence.EvidenceJson.read(input, DemoEvidence.class);
    requireExpectedEvidence(evidence);
    return evidence;
  }

  public static void writeArm(Path output, DemoArmEvidence evidence) throws IOException {
    requireExpectedArm(evidence);
    DemoArmEvidence.EvidenceJson.write(output, evidence);
  }

  public static void write(Path output, DemoEvidence evidence) throws IOException {
    requireExpectedEvidence(evidence);
    DemoArmEvidence.EvidenceJson.write(output, evidence);
  }

  public static String canonicalJson(Object evidence) {
    if (!(evidence instanceof DemoArmEvidence || evidence instanceof DemoEvidence)) {
      throw new IllegalArgumentException("only normalized demo evidence can be serialized");
    }
    return DemoArmEvidence.EvidenceJson.canonicalJson(evidence);
  }

  private static void requireExpectedEvidence(DemoEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    requireEqual("candidate ids", EXPECTED_CANDIDATES, evidence.candidateIds());
    requireEqual("control order", EXPECTED_CANDIDATES, evidence.controlExecutedOrder());
    requireEqual("memory order", EXPECTED_MEMORY_ORDER, evidence.memoryExecutedOrder());
    requireEqual("control metric", 3, evidence.controlActionsBeforeRegression());
    requireEqual("memory metric", 1, evidence.memoryActionsBeforeRegression());
    requireExpectedEvidenceProofs(evidence);
  }

  private static void requireExpectedEvidenceProofs(DemoEvidence evidence) {
    requireEqual("identity proof", EXPECTED_IDENTITY, evidence.stableIdentityProof());
    requireEqual("ambiguity proof", EXPECTED_AMBIGUITY, evidence.ambiguityProof());
    requireEqual("stateless summary", EXPECTED_STATELESS, evidence.statelessReportSummary());
    requireEqual("memory summary", EXPECTED_MEMORY, evidence.memoryReportSummary());
    requireEqual("duplicate retry", IngestResult.NO_OP, evidence.duplicateRetryResult());
    requireEqual(
        "conflict result",
        ConflictProbeResult.EVENT_CONTENT_REJECTED,
        evidence.conflictingDuplicateResult());
    requireEqual(
        "completion result",
        CompletionProbeResult.UNFINALIZED_REPORT_BLOCKED,
        evidence.missingCompletionResult());
    if (!evidence.hasZeroE2EventsAtPlanning()
        || !evidence.shuffleDigestEquality()
        || evidence.transactionFailureLeavesPartialState()) {
      throw new IllegalArgumentException("final demo evidence contains a failed proof");
    }
  }

  private static void requireEqualProofs(DemoArmEvidence control, DemoArmEvidence memory) {
    requireEqual("schema version", control.evidenceSchemaVersion(), memory.evidenceSchemaVersion());
    requireEqual(
        "stateless digest", control.statelessReportDigest(), memory.statelessReportDigest());
    requireEqual("identity proof", control.stableIdentityProof(), memory.stableIdentityProof());
    requireEqual("ambiguity proof", control.ambiguityProof(), memory.ambiguityProof());
    requireEqual(
        "stateless summary", control.statelessReportSummary(), memory.statelessReportSummary());
    requireEqual("memory summary", control.memoryReportSummary(), memory.memoryReportSummary());
    requireEqual(
        "duplicate retry", control.duplicateRetryResult(), memory.duplicateRetryResult());
    requireEqual(
        "conflict result",
        control.conflictingDuplicateResult(),
        memory.conflictingDuplicateResult());
    requireEqual(
        "completion result", control.missingCompletionResult(), memory.missingCompletionResult());
    requireEqual(
        "shuffle equality", control.shuffleDigestEquality(), memory.shuffleDigestEquality());
    requireEqual(
        "rollback state",
        control.rollbackProbeHasPartialState(),
        memory.rollbackProbeHasPartialState());
  }

  private static void requireCandidateEventEquality(
      DemoArmEvidence control, DemoArmEvidence memory) {
    for (String candidateId : control.candidateIds()) {
      Set<String> controlIds = Set.copyOf(control.candidateSourceEventIds().get(candidateId));
      Set<String> memoryIds = Set.copyOf(memory.candidateSourceEventIds().get(candidateId));
      requireEqual("source events for " + candidateId, controlIds, memoryIds);
    }
  }

  private static List<String> copyUniqueNonempty(List<String> values, String field) {
    List<String> copy = List.copyOf(Objects.requireNonNull(values, field));
    if (copy.isEmpty() || copy.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException(field + " must contain nonblank values");
    }
    if (new HashSet<>(copy).size() != copy.size()) {
      throw new IllegalArgumentException(field + " must contain unique values");
    }
    return copy;
  }

  private static void requireCompleteOrder(
      List<String> candidates, List<String> order, String field) {
    if (order.size() != candidates.size() || !Set.copyOf(order).equals(Set.copyOf(candidates))) {
      throw new IllegalArgumentException(field + " must cover exactly the candidates");
    }
  }

  private static void requirePosition(int value, List<String> order, String field) {
    if (value < 1 || value > order.size()) {
      throw new IllegalArgumentException(field + " is not a one-based executed position");
    }
  }

  private static void requireEqual(String field, Object expected, Object actual) {
    if (!Objects.equals(expected, actual)) {
      throw new IllegalArgumentException("demo evidence has invalid " + field);
    }
  }
}
