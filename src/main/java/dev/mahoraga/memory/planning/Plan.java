package dev.mahoraga.memory.planning;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The deterministic planner output: at most {@code min(action budget,
 * candidate count)} unique candidate ids in execution order. This value does
 * not execute the plan.
 */
public record Plan(List<String> orderedCandidateIds) {

  public Plan {
    orderedCandidateIds = List.copyOf(Objects.requireNonNull(orderedCandidateIds, "orderedCandidateIds"));
    Set<String> unique = new HashSet<>(orderedCandidateIds);
    if (unique.size() != orderedCandidateIds.size()) {
      throw new IllegalArgumentException("a plan lists each candidate at most once");
    }
  }
}
