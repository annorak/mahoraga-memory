package dev.mahoraga.memory.contract;

/**
 * Tenant and engagement identity supplied by a trusted caller, such as the
 * fixture runner. Never taken from payload JSON and never part of the canonical
 * source hash.
 */
public record TrustedContext(String tenantId, String engagementId) {

  public TrustedContext {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("trusted context tenantId must be nonblank");
    }
    if (engagementId == null || engagementId.isBlank()) {
      throw new IllegalArgumentException("trusted context engagementId must be nonblank");
    }
  }
}
