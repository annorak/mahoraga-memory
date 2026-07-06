package dev.mahoraga.memory.planning;

import dev.mahoraga.memory.fixture.DeploymentTarget;
import java.util.Objects;

/**
 * One candidate check the planner may order: an opaque nonblank candidate id,
 * the authoritative Deployment key it targets, and the verification key that
 * joins it to prior coverage. By construction it carries no scenario label,
 * frozen outcome, internal asset UUID, E2 fact, or field naming an expected
 * order.
 */
public record CandidateTest(String candidateId, DeploymentTarget target, String verificationKey) {

  public CandidateTest {
    requireNonblank(candidateId, "candidateId");
    Objects.requireNonNull(target, "target");
    requireNonblank(verificationKey, "verificationKey");
  }

  private static void requireNonblank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("candidate " + field + " must be nonblank");
    }
  }
}
