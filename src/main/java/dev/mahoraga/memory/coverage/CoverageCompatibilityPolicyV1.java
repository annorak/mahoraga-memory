package dev.mahoraga.memory.coverage;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;

/**
 * Coverage compatibility policy version 1: an attempt covers a finding only
 * under exact equality of tenant, canonical asset, verification key, check
 * version, and relevant-context fingerprint, with both recorded
 * compatibility-policy versions exactly {@value #POLICY_VERSION}. There is no
 * fuzzy version or context matching, and a newer check version never covers an
 * older baseline. Only a compatible completed {@code not_detected} attempt is
 * resolving evidence; failed, blocked, partial, skipped, inconclusive, or
 * incompatible attempts never close exposure.
 */
public final class CoverageCompatibilityPolicyV1 {

  public static final int POLICY_VERSION = 1;

  private CoverageCompatibilityPolicyV1() {}

  public static boolean isCompatible(
      RecordedTestAttempt attempt, FindingVerificationBaseline finding) {
    return attempt.compatibilityPolicyVersion() == POLICY_VERSION
        && finding.compatibilityPolicyVersion() == POLICY_VERSION
        && attempt.tenantId().equals(finding.tenantId())
        && attempt.canonicalAssetId().equals(finding.canonicalAssetId())
        && attempt.verificationKey().equals(finding.verificationKey())
        && attempt.checkVersion().equals(finding.checkVersion())
        && attempt.relevantContextHash().equals(finding.relevantContextHash());
  }

  public static boolean isResolvingEvidence(
      RecordedTestAttempt attempt, FindingVerificationBaseline finding) {
    return isCompatible(attempt, finding)
        && attempt.executionStatus() == ExecutionStatus.COMPLETED
        && attempt.result() == TestResult.NOT_DETECTED;
  }
}
