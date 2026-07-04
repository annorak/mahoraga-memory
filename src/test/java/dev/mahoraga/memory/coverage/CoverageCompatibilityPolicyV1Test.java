package dev.mahoraga.memory.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.contract.RelevantContext;
import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import dev.mahoraga.memory.finding.RelevantContextFingerprint;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the pure policy-v1 predicate: all six dimensions must match exactly,
 * both policy versions must be exactly 1, and only a compatible completed
 * not_detected attempt is resolving evidence.
 */
class CoverageCompatibilityPolicyV1Test {

  private static final UUID ASSET = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_ASSET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String KEY = "check-xss-1";
  private static final String VERSION = "1.0";
  private static final String HASH = "a".repeat(64);
  private static final String OTHER_HASH = "b".repeat(64);

  @Test
  void fullExactMatchIsCompatible() {
    assertTrue(CoverageCompatibilityPolicyV1.isCompatible(exactAttempt(), baseline(HASH)));
  }

  @Test
  void changingExactlyOneDimensionIsIncompatible() {
    FindingVerificationBaseline finding = baseline(HASH);

    assertIncompatible(attempt("t-other", ASSET, KEY, VERSION, HASH, 1), finding);
    assertIncompatible(attempt("t-1", OTHER_ASSET, KEY, VERSION, HASH, 1), finding);
    assertIncompatible(attempt("t-1", ASSET, "check-other", VERSION, HASH, 1), finding);
    assertIncompatible(attempt("t-1", ASSET, KEY, "2.0", HASH, 1), finding);
    assertIncompatible(attempt("t-1", ASSET, KEY, VERSION, OTHER_HASH, 1), finding);
    assertIncompatible(attempt("t-1", ASSET, KEY, VERSION, HASH, 2), finding);
  }

  @Test
  void policyVersionMustBeExactlyOneOnBothSides() {
    FindingVerificationBaseline findingV2 =
        new FindingVerificationBaseline("t-1", ASSET, KEY, VERSION, HASH, 2);

    assertFalse(CoverageCompatibilityPolicyV1.isCompatible(exactAttempt(), findingV2));
    // Equal versions are not enough: 2 on both sides is still incompatible.
    assertFalse(
        CoverageCompatibilityPolicyV1.isCompatible(
            attempt("t-1", ASSET, KEY, VERSION, HASH, 2), findingV2));
  }

  @Test
  void unboundTargetAddressChurnKeepsTheFingerprintAndCompatibility() {
    String findingHash = RelevantContextFingerprint.hash(context(false, "10.0.0.10"));
    String attemptHash = RelevantContextFingerprint.hash(context(false, "10.0.0.42"));

    assertEquals(findingHash, attemptHash);
    assertTrue(
        CoverageCompatibilityPolicyV1.isCompatible(
            attempt("t-1", ASSET, KEY, VERSION, attemptHash, 1), baseline(findingHash)));
  }

  @Test
  void addressBoundTargetAddressChurnChangesTheFingerprintAndCompatibility() {
    String findingHash = RelevantContextFingerprint.hash(context(true, "10.0.0.10"));
    String attemptHash = RelevantContextFingerprint.hash(context(true, "10.0.0.42"));

    assertNotEquals(findingHash, attemptHash);
    assertFalse(
        CoverageCompatibilityPolicyV1.isCompatible(
            attempt("t-1", ASSET, KEY, VERSION, attemptHash, 1), baseline(findingHash)));
  }

  @Test
  void onlyACompatibleCompletedNotDetectedAttemptIsResolvingEvidence() {
    FindingVerificationBaseline finding = baseline(HASH);

    assertTrue(
        CoverageCompatibilityPolicyV1.isResolvingEvidence(
            outcome(ExecutionStatus.COMPLETED, TestResult.NOT_DETECTED), finding));
    assertFalse(
        CoverageCompatibilityPolicyV1.isResolvingEvidence(
            outcome(ExecutionStatus.COMPLETED, TestResult.DETECTED), finding));
    for (ExecutionStatus status :
        new ExecutionStatus[] {
          ExecutionStatus.FAILED,
          ExecutionStatus.BLOCKED,
          ExecutionStatus.PARTIAL,
          ExecutionStatus.SKIPPED
        }) {
      assertFalse(
          CoverageCompatibilityPolicyV1.isResolvingEvidence(outcome(status, null), finding),
          status + " with null result must not resolve");
      assertFalse(
          CoverageCompatibilityPolicyV1.isResolvingEvidence(
              outcome(status, TestResult.INCONCLUSIVE), finding),
          status + " with inconclusive result must not resolve");
    }
  }

  @Test
  void anIncompatibleCompletedNegativeIsNotResolvingEvidence() {
    RecordedTestAttempt negative =
        new RecordedTestAttempt(
            "t-1", "evt-1", ASSET, KEY, "2.0", HASH, 1,
            ExecutionStatus.COMPLETED, TestResult.NOT_DETECTED);

    assertFalse(CoverageCompatibilityPolicyV1.isResolvingEvidence(negative, baseline(HASH)));
  }

  private static void assertIncompatible(
      RecordedTestAttempt attempt, FindingVerificationBaseline finding) {
    assertFalse(CoverageCompatibilityPolicyV1.isCompatible(attempt, finding));
  }

  private static FindingVerificationBaseline baseline(String contextHash) {
    return new FindingVerificationBaseline("t-1", ASSET, KEY, VERSION, contextHash, 1);
  }

  private static RecordedTestAttempt exactAttempt() {
    return attempt("t-1", ASSET, KEY, VERSION, HASH, 1);
  }

  private static RecordedTestAttempt attempt(
      String tenantId, UUID assetId, String key, String checkVersion, String hash, int policy) {
    return new RecordedTestAttempt(
        tenantId, "evt-1", assetId, key, checkVersion, hash, policy,
        ExecutionStatus.COMPLETED, TestResult.NOT_DETECTED);
  }

  private static RecordedTestAttempt outcome(ExecutionStatus status, TestResult result) {
    return new RecordedTestAttempt(
        "t-1", "evt-1", ASSET, KEY, VERSION, HASH, 1, status, result);
  }

  private static RelevantContext context(boolean isAddressBound, String targetAddress) {
    return new RelevantContext(
        "https", 443, "/login", Map.of("depth", 2), targetAddress, isAddressBound);
  }
}
