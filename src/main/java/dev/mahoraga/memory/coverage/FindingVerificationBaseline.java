package dev.mahoraga.memory.coverage;

import java.util.Objects;
import java.util.UUID;

/**
 * The immutable verification baseline recorded with a finding at creation: the
 * finding side of coverage compatibility. Always read back from the recorded
 * finding row, so matching depends on persisted facts rather than whatever a
 * later observation claims.
 */
public record FindingVerificationBaseline(
    String tenantId,
    UUID canonicalAssetId,
    String verificationKey,
    String checkVersion,
    String relevantContextHash,
    int compatibilityPolicyVersion) {

  public FindingVerificationBaseline {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(canonicalAssetId, "canonicalAssetId");
    Objects.requireNonNull(verificationKey, "verificationKey");
    Objects.requireNonNull(checkVersion, "checkVersion");
    Objects.requireNonNull(relevantContextHash, "relevantContextHash");
  }
}
