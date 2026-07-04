package dev.mahoraga.memory.coverage;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable persisted test attempt: the coverage fact that this exact check
 * ran, or tried to run, against one canonical asset. The canonical asset and
 * relevant-context fingerprint are computed by Mahoraga at ingestion, never
 * accepted from the producer, and compatibility is always evaluated from these
 * recorded values rather than from current Pod observations. The result is null
 * exactly when a non-completed attempt established nothing.
 */
public record RecordedTestAttempt(
    String tenantId,
    String sourceEventId,
    UUID canonicalAssetId,
    String verificationKey,
    String checkVersion,
    String relevantContextHash,
    int compatibilityPolicyVersion,
    ExecutionStatus executionStatus,
    TestResult result) {

  public RecordedTestAttempt {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(canonicalAssetId, "canonicalAssetId");
    Objects.requireNonNull(verificationKey, "verificationKey");
    Objects.requireNonNull(checkVersion, "checkVersion");
    Objects.requireNonNull(relevantContextHash, "relevantContextHash");
    Objects.requireNonNull(executionStatus, "executionStatus");
  }
}
