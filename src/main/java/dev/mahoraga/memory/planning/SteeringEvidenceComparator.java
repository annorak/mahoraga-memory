package dev.mahoraga.memory.planning;

import dev.mahoraga.memory.planning.SteeringArmEvidence.ArmMode;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Set;

/**
 * The pure two-arm equality guard. It accepts exactly one control and one
 * memory evidence value and rejects anything less: a missing or same-mode
 * pair, an arm that planned after E2 data existed, unequal E1 state, unequal
 * controlled inputs, differently executed candidate sets, or unequal final
 * facts and reports. Only executed order and the causative metric may differ
 * between valid arms.
 */
public final class SteeringEvidenceComparator {

  @Inject
  public SteeringEvidenceComparator() {}

  public SteeringComparison compare(SteeringArmEvidence control, SteeringArmEvidence memory) {
    Objects.requireNonNull(control, "control");
    Objects.requireNonNull(memory, "memory");
    if (control.armMode() != ArmMode.CONTROL || memory.armMode() != ArmMode.MEMORY) {
      throw new IllegalArgumentException(
          "the comparison requires one CONTROL and one MEMORY arm, received %s and %s"
              .formatted(control.armMode(), memory.armMode()));
    }
    if (!control.hasZeroE2EventsAtPlanning() || !memory.hasZeroE2EventsAtPlanning()) {
      throw new IllegalArgumentException(
          "both arms must record that planning saw zero E2 events");
    }
    requireEqual("planner boundary hash", control.plannerBoundaryHash(), memory.plannerBoundaryHash());
    requireEqual("E1 semantic digest", control.e1SemanticDigest(), memory.e1SemanticDigest());
    requireEqual(
        "controlled-input digest", control.candidateInputDigest(), memory.candidateInputDigest());
    requireEqual("candidate ids", control.candidateIds(), memory.candidateIds());
    if (!Set.copyOf(control.executedOrder()).equals(Set.copyOf(memory.executedOrder()))) {
      throw new IllegalArgumentException(
          "both arms must execute the same candidates: %s vs %s"
              .formatted(control.executedOrder(), memory.executedOrder()));
    }
    requireEqual("E2 fact-set digest", control.e2FactSetDigest(), memory.e2FactSetDigest());
    requireEqual("memory report digest", control.memoryReportDigest(), memory.memoryReportDigest());
    return new SteeringComparison(
        control.executedOrder(),
        memory.executedOrder(),
        control.actionsBeforeRegression(),
        memory.actionsBeforeRegression(),
        control.e1SemanticDigest(),
        control.candidateInputDigest(),
        control.e2FactSetDigest(),
        control.memoryReportDigest());
  }

  private static void requireEqual(String field, Object controlValue, Object memoryValue) {
    if (!controlValue.equals(memoryValue)) {
      throw new IllegalArgumentException(
          "control and memory evidence disagree on %s: %s vs %s"
              .formatted(field, controlValue, memoryValue));
    }
  }
}
