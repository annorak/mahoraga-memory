package dev.mahoraga.memory.planning;

import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The fixed ordering policy, version 1. Memory off (no features) orders by
 * candidate id ascending; memory on orders candidates with a prior verified
 * resolution first, tie-breaking by candidate id. There is no weight,
 * severity, cost, recency, randomness, configuration, or model, so equal
 * requests produce equal plans across retry and restart.
 */
public final class DeterministicPlanner {

  @Inject
  public DeterministicPlanner() {}

  public Plan plan(PlannerRequest request) {
    Objects.requireNonNull(request, "request");
    List<String> ordered =
        request.candidates().stream()
            .sorted(candidateOrder(request))
            .limit(Math.min(request.actionBudget(), request.candidates().size()))
            .map(CandidateTest::candidateId)
            .toList();
    return new Plan(ordered);
  }

  private static Comparator<CandidateTest> candidateOrder(PlannerRequest request) {
    Comparator<CandidateTest> byCandidateId = Comparator.comparing(CandidateTest::candidateId);
    if (!request.isMemoryEnabled()) {
      return byCandidateId;
    }
    // Validated one-to-one coverage guarantees a feature exists per candidate;
    // reversed boolean order puts prior verified resolutions first.
    Map<String, Boolean> priorResolutionByCandidateId =
        request.memoryFeatures().stream()
            .collect(
                Collectors.toMap(
                    MemoryFeature::candidateId, MemoryFeature::hasPriorVerifiedResolution));
    return Comparator.<CandidateTest, Boolean>comparing(
            candidate -> priorResolutionByCandidateId.get(candidate.candidateId()),
            Comparator.reverseOrder())
        .thenComparing(byCandidateId);
  }
}
