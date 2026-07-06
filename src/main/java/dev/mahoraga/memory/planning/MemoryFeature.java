package dev.mahoraga.memory.planning;

/**
 * The one memory signal ordering policy version 1 uses: whether the
 * candidate's exact target and verification key still fold to a verified
 * resolution at the supplied boundary. Derived from persisted facts by
 * {@link PreEngagementMemoryQuery}, never supplied by a fixture.
 */
public record MemoryFeature(String candidateId, boolean hasPriorVerifiedResolution) {

  public MemoryFeature {
    if (candidateId == null || candidateId.isBlank()) {
      throw new IllegalArgumentException("memory feature candidateId must be nonblank");
    }
  }
}
