package dev.mahoraga.memory.planning;

import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The complete typed planner input. The tenant comes from trusted context, the
 * knowledge boundary is always explicit (nothing infers "latest"), and the
 * memory features are the only input difference between the two arms: an empty
 * set means memory off; otherwise exactly one derived feature per candidate.
 * Duplicate candidates, non-positive budgets, and missing, duplicate, or
 * unknown feature entries reject at construction.
 */
public record PlannerRequest(
    String tenantId,
    List<CandidateTest> candidates,
    int actionBudget,
    KnowledgeBoundary knowledgeBoundary,
    List<MemoryFeature> memoryFeatures) {

  public PlannerRequest {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("planner request tenantId must be nonblank");
    }
    Objects.requireNonNull(knowledgeBoundary, "knowledgeBoundary");
    if (actionBudget <= 0) {
      throw new IllegalArgumentException("planner request actionBudget must be positive");
    }
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    memoryFeatures = List.copyOf(Objects.requireNonNull(memoryFeatures, "memoryFeatures"));
    Set<String> candidateIds = new HashSet<>();
    for (CandidateTest candidate : candidates) {
      if (!candidateIds.add(candidate.candidateId())) {
        throw new IllegalArgumentException(
            "planner request names candidate %s more than once"
                .formatted(candidate.candidateId()));
      }
    }
    if (!memoryFeatures.isEmpty()) {
      requireExactlyOneFeaturePerCandidate(candidateIds, memoryFeatures);
    }
  }

  /** Memory off passes no features; memory on requires exact one-to-one coverage. */
  public boolean isMemoryEnabled() {
    return !memoryFeatures.isEmpty();
  }

  private static void requireExactlyOneFeaturePerCandidate(
      Set<String> candidateIds, List<MemoryFeature> memoryFeatures) {
    Set<String> featureIds = new HashSet<>();
    for (MemoryFeature feature : memoryFeatures) {
      if (!featureIds.add(feature.candidateId())) {
        throw new IllegalArgumentException(
            "memory features name candidate %s more than once".formatted(feature.candidateId()));
      }
      if (!candidateIds.contains(feature.candidateId())) {
        throw new IllegalArgumentException(
            "memory feature names unknown candidate %s".formatted(feature.candidateId()));
      }
    }
    if (!featureIds.equals(candidateIds)) {
      Set<String> missing = new HashSet<>(candidateIds);
      missing.removeAll(featureIds);
      throw new IllegalArgumentException(
          "memory on requires exactly one feature per candidate; missing " + missing);
    }
  }
}
