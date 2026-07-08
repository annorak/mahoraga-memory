package dev.mahoraga.memory.planning;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic evidence for one executed experiment arm. Producers derive values
 * from persisted state or explicit inputs, not clocks, labels, or expected
 * outputs. Construction validates required fields and candidate/event
 * relationships; {@link SteeringEvidenceComparator} validates the cross-arm
 * proof. Canonical fact and report digests exclude random internal IDs.
 */
public record SteeringArmEvidence(
    ArmMode armMode,
    String candidateInputDigest,
    List<String> candidateIds,
    List<String> executedOrder,
    Map<String, List<String>> candidateSourceEventIds,
    int actionsBeforeRegression,
    String plannerBoundaryHash,
    boolean hasZeroE2EventsAtPlanning,
    String e1SemanticDigest,
    String e2FactSetDigest,
    String memoryReportDigest) {

  /** Which arm produced the evidence: features omitted or features derived. */
  public enum ArmMode {
    CONTROL,
    MEMORY
  }

  public SteeringArmEvidence {
    Objects.requireNonNull(armMode, "armMode");
    requireNonblank(candidateInputDigest, "candidateInputDigest");
    requireNonblank(plannerBoundaryHash, "plannerBoundaryHash");
    requireNonblank(e1SemanticDigest, "e1SemanticDigest");
    requireNonblank(e2FactSetDigest, "e2FactSetDigest");
    requireNonblank(memoryReportDigest, "memoryReportDigest");
    candidateIds = requireUniqueNonempty(candidateIds, "candidateIds");
    executedOrder = requireUniqueNonempty(executedOrder, "executedOrder");
    if (!candidateIds.containsAll(executedOrder)) {
      throw new IllegalArgumentException("executed order names candidates outside the input set");
    }
    candidateSourceEventIds = copyExecutedEvents(candidateSourceEventIds, executedOrder);
    if (actionsBeforeRegression < 1 || actionsBeforeRegression > executedOrder.size()) {
      throw new IllegalArgumentException(
          "actionsBeforeRegression %d is not an executed one-based position"
              .formatted(actionsBeforeRegression));
    }
  }

  private static List<String> requireUniqueNonempty(List<String> values, String field) {
    List<String> copied = List.copyOf(Objects.requireNonNull(values, field));
    if (copied.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be empty");
    }
    if (new HashSet<>(copied).size() != copied.size()) {
      throw new IllegalArgumentException(field + " must not repeat a candidate");
    }
    return copied;
  }

  private static Map<String, List<String>> copyExecutedEvents(
      Map<String, List<String>> candidateSourceEventIds, List<String> executedOrder) {
    Objects.requireNonNull(candidateSourceEventIds, "candidateSourceEventIds");
    if (!candidateSourceEventIds.keySet().equals(Set.copyOf(executedOrder))) {
      throw new IllegalArgumentException(
          "candidateSourceEventIds must cover exactly the executed candidates");
    }
    Map<String, List<String>> copied = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : candidateSourceEventIds.entrySet()) {
      if (entry.getValue().isEmpty()) {
        throw new IllegalArgumentException(
            "executed candidate %s records no source events".formatted(entry.getKey()));
      }
      copied.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(copied);
  }

  private static void requireNonblank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("arm evidence requires a nonblank " + field);
    }
  }
}
