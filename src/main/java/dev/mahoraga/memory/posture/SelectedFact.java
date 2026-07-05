package dev.mahoraga.memory.posture;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable facts selected at a knowledge boundary, carrying the source
 * lineage the fold orders by ({@code occurredAt} is the validated effective
 * time; stream and sequence are the deterministic tie-breakers) plus the
 * semantic identity and coverage fields folding and reporting require.
 * Operational recorded time, raw payloads, and fixture vocabulary are
 * deliberately absent.
 */
public sealed interface SelectedFact {

  String tenantId();

  String engagementId();

  String sourceEventId();

  String sourceStreamId();

  long sourceSequence();

  Instant occurredAt();

  /** One recorded detection of a finding, joined to its immutable identity and baseline. */
  record FindingOccurrence(
      String tenantId,
      String engagementId,
      String sourceEventId,
      String sourceStreamId,
      long sourceSequence,
      Instant occurredAt,
      UUID findingId,
      UUID canonicalAssetId,
      String vulnClass,
      String normalizedLocationSignature,
      int matchKeyVersion,
      String verificationKey,
      String checkVersion,
      String relevantContextHash,
      int compatibilityPolicyVersion)
      implements SelectedFact {

    public FindingOccurrence {
      requireCommon(tenantId, engagementId, sourceEventId, sourceStreamId, occurredAt);
      Objects.requireNonNull(findingId, "findingId");
      Objects.requireNonNull(canonicalAssetId, "canonicalAssetId");
      Objects.requireNonNull(vulnClass, "vulnClass");
      Objects.requireNonNull(normalizedLocationSignature, "normalizedLocationSignature");
      Objects.requireNonNull(verificationKey, "verificationKey");
      Objects.requireNonNull(checkVersion, "checkVersion");
      Objects.requireNonNull(relevantContextHash, "relevantContextHash");
    }
  }

  /** One recorded test attempt; standalone attempts without any in-bound finding stay valid. */
  record TestAttempt(
      String tenantId,
      String engagementId,
      String sourceEventId,
      String sourceStreamId,
      long sourceSequence,
      Instant occurredAt,
      UUID canonicalAssetId,
      String verificationKey,
      String checkVersion,
      String relevantContextHash,
      int compatibilityPolicyVersion,
      ExecutionStatus executionStatus,
      TestResult result)
      implements SelectedFact {

    public TestAttempt {
      requireCommon(tenantId, engagementId, sourceEventId, sourceStreamId, occurredAt);
      Objects.requireNonNull(canonicalAssetId, "canonicalAssetId");
      Objects.requireNonNull(verificationKey, "verificationKey");
      Objects.requireNonNull(checkVersion, "checkVersion");
      Objects.requireNonNull(relevantContextHash, "relevantContextHash");
      Objects.requireNonNull(executionStatus, "executionStatus");
    }
  }

  private static void requireCommon(
      String tenantId,
      String engagementId,
      String sourceEventId,
      String sourceStreamId,
      Instant occurredAt) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(engagementId, "engagementId");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(sourceStreamId, "sourceStreamId");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
