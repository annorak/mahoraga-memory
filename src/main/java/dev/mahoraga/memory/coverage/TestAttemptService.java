package dev.mahoraga.memory.coverage;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestAttempt;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.finding.FindingId;
import dev.mahoraga.memory.finding.RelevantContextFingerprint;
import dev.mahoraga.memory.identity.AssetId;
import dev.mahoraga.memory.identity.AssetIdentityService;
import jakarta.inject.Inject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.jdbi.v3.core.Handle;

/**
 * Persists immutable test attempts and answers coverage questions for a
 * finding under {@link CoverageCompatibilityPolicyV1}. An attempt resolves its
 * canonical asset from its own authoritative Deployment key, so it may arrive
 * before any matching finding; queries join persisted attempts to the recorded
 * finding baseline, so results never depend on arrival order. Runs entirely on
 * the caller's active ingestion handle and never opens its own transaction.
 */
public final class TestAttemptService {

  private final AssetIdentityService assetIdentityService;

  @Inject
  public TestAttemptService(AssetIdentityService assetIdentityService) {
    this.assetIdentityService =
        Objects.requireNonNull(assetIdentityService, "assetIdentityService");
  }

  /**
   * Resolves the attempt's authoritative Deployment independently of any
   * finding, computes the policy-v1 context fingerprint server-side, and
   * appends one immutable attempt row for this source event. Any failure
   * propagates so the source event rolls back with every write made here. A
   * detected attempt records coverage only; it never synthesizes a finding or
   * an occurrence.
   */
  public RecordedTestAttempt recordTestAttempt(
      Handle handle, TrustedContext context, String sourceEventId, TestAttempt payload) {
    AssetId assetId =
        assetIdentityService.resolveAuthoritativeDeployment(
            handle, context, sourceEventId, payload.clusterId(), payload.resourceUid());
    RecordedTestAttempt attempt =
        new RecordedTestAttempt(
            context.tenantId(),
            sourceEventId,
            assetId.value(),
            payload.verificationKey(),
            payload.checkVersion(),
            RelevantContextFingerprint.hash(payload.relevantContext()),
            payload.compatibilityPolicyVersion(),
            payload.executionStatus(),
            payload.result());
    insertAttempt(handle, attempt);
    return attempt;
  }

  /** All attempts compatible with the finding's recorded baseline. */
  public List<RecordedTestAttempt> findCompatibleAttempts(
      Handle handle, TrustedContext context, FindingId findingId) {
    return findAttempts(handle, context, findingId, CoverageCompatibilityPolicyV1::isCompatible);
  }

  /** Only compatible completed {@code not_detected} attempts verify resolution. */
  public List<RecordedTestAttempt> findResolvingEvidence(
      Handle handle, TrustedContext context, FindingId findingId) {
    return findAttempts(
        handle, context, findingId, CoverageCompatibilityPolicyV1::isResolvingEvidence);
  }

  /**
   * Candidate selection joins on the recorded asset and verification key; the
   * policy predicate then decides, so the exact compatibility rule lives in
   * one place. Compatibility requires equality on both join columns, so the
   * narrower SQL selection can never exclude a compatible attempt.
   */
  private List<RecordedTestAttempt> findAttempts(
      Handle handle,
      TrustedContext context,
      FindingId findingId,
      BiPredicate<RecordedTestAttempt, FindingVerificationBaseline> policy) {
    FindingVerificationBaseline baseline = readBaseline(handle, context, findingId);
    return candidateAttempts(handle, baseline).stream()
        .filter(attempt -> policy.test(attempt, baseline))
        .toList();
  }

  private FindingVerificationBaseline readBaseline(
      Handle handle, TrustedContext context, FindingId findingId) {
    return handle
        .createQuery(
            """
            SELECT canonical_asset_id, verification_key, check_version,
              relevant_context_hash, compatibility_policy_version
            FROM findings
            WHERE tenant_id = :tenantId AND finding_id = :findingId
            """)
        .bind("tenantId", context.tenantId())
        .bind("findingId", findingId.value())
        .map(
            (rs, ctx) ->
                new FindingVerificationBaseline(
                    context.tenantId(),
                    rs.getObject("canonical_asset_id", UUID.class),
                    rs.getString("verification_key"),
                    rs.getString("check_version"),
                    rs.getString("relevant_context_hash"),
                    rs.getInt("compatibility_policy_version")))
        .findOne()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "finding %s is not recorded for tenant %s"
                        .formatted(findingId.value(), context.tenantId())));
  }

  private List<RecordedTestAttempt> candidateAttempts(
      Handle handle, FindingVerificationBaseline baseline) {
    return handle
        .createQuery(
            """
            SELECT source_event_id, canonical_asset_id, verification_key, check_version,
              relevant_context_hash, compatibility_policy_version, execution_status, result
            FROM test_attempts
            WHERE tenant_id = :tenantId AND canonical_asset_id = :canonicalAssetId
              AND verification_key = :verificationKey
            ORDER BY source_event_id
            """)
        .bind("tenantId", baseline.tenantId())
        .bind("canonicalAssetId", baseline.canonicalAssetId())
        .bind("verificationKey", baseline.verificationKey())
        .map((rs, ctx) -> mapAttempt(rs, baseline.tenantId()))
        .list();
  }

  private static RecordedTestAttempt mapAttempt(ResultSet rs, String tenantId)
      throws SQLException {
    String result = rs.getString("result");
    return new RecordedTestAttempt(
        tenantId,
        rs.getString("source_event_id"),
        rs.getObject("canonical_asset_id", UUID.class),
        rs.getString("verification_key"),
        rs.getString("check_version"),
        rs.getString("relevant_context_hash"),
        rs.getInt("compatibility_policy_version"),
        ExecutionStatus.fromWire(rs.getString("execution_status")),
        result == null ? null : TestResult.fromWire(result));
  }

  private void insertAttempt(Handle handle, RecordedTestAttempt attempt) {
    handle
        .createUpdate(
            """
            INSERT INTO test_attempts (tenant_id, source_event_id, canonical_asset_id,
              verification_key, check_version, relevant_context_hash,
              compatibility_policy_version, execution_status, result)
            VALUES (:tenantId, :sourceEventId, :canonicalAssetId, :verificationKey,
              :checkVersion, :relevantContextHash, :compatibilityPolicyVersion,
              :executionStatus, :result)
            """)
        .bind("tenantId", attempt.tenantId())
        .bind("sourceEventId", attempt.sourceEventId())
        .bind("canonicalAssetId", attempt.canonicalAssetId())
        .bind("verificationKey", attempt.verificationKey())
        .bind("checkVersion", attempt.checkVersion())
        .bind("relevantContextHash", attempt.relevantContextHash())
        .bind("compatibilityPolicyVersion", attempt.compatibilityPolicyVersion())
        .bind("executionStatus", attempt.executionStatus().wireValue())
        .bind("result", attempt.result() == null ? null : attempt.result().wireValue())
        .execute();
  }
}
