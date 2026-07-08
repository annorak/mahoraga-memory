package dev.mahoraga.memory.finding;

import dev.mahoraga.memory.contract.SourcePayload.FindingObservation;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.identity.AssetId;
import dev.mahoraga.memory.identity.AssetIdentityService;
import dev.mahoraga.memory.ingest.IngestionFaultHook;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Handle;

/**
 * Finding match policy version 1: identity is exactly {@code (tenant, canonical
 * asset, vuln_class, normalized_location_signature, match_key_version)} with
 * server-supplied version 1 and exact string matching of canonical inputs. The
 * four-part verification baseline recorded at creation is immutable. There is
 * deliberately no finding-update SQL, and every accepted detection appends one
 * occurrence. All work uses the caller's active ingestion handle.
 */
public final class FindingIdentityService {

  public static final int MATCH_KEY_VERSION = 1;

  private final AssetIdentityService assetIdentityService;
  private final IngestionFaultHook faultHook;

  @Inject
  public FindingIdentityService(
      AssetIdentityService assetIdentityService, IngestionFaultHook faultHook) {
    this.assetIdentityService =
        Objects.requireNonNull(assetIdentityService, "assetIdentityService");
    this.faultHook = Objects.requireNonNull(faultHook, "faultHook");
  }

  /**
   * Resolves the authoritative asset, inserts-or-reads the finding for the
   * match identity, enforces the recorded baseline, and appends one immutable
   * occurrence for this source event. Any failure propagates, so the ingestion
   * transaction rolls back the source event and every write made here.
   */
  public FindingId recordFindingObservation(
      Handle handle, TrustedContext context, String sourceEventId, FindingObservation payload) {
    AssetId assetId =
        assetIdentityService.resolveAuthoritativeDeployment(
            handle, context, sourceEventId, payload.clusterId(), payload.resourceUid());
    String contextHash = RelevantContextFingerprint.hash(payload.relevantContext());
    insertFindingIfAbsent(handle, context, assetId, payload, contextHash);
    FindingRow recorded = readFinding(handle, context, assetId, payload, sourceEventId);
    requireRecordedBaseline(recorded, payload, contextHash, context, sourceEventId);
    faultHook.afterStage(IngestionFaultHook.Stage.AFTER_FINDING_RESOLUTION, handle);
    insertOccurrence(handle, context, sourceEventId, recorded.findingId());
    faultHook.afterStage(IngestionFaultHook.Stage.AFTER_FINDING_OCCURRENCE_WRITE, handle);
    return new FindingId(recorded.findingId());
  }

  private void insertFindingIfAbsent(
      Handle handle,
      TrustedContext context,
      AssetId assetId,
      FindingObservation payload,
      String contextHash) {
    handle
        .createUpdate(
            """
            INSERT INTO findings (tenant_id, finding_id, canonical_asset_id, vuln_class,
              normalized_location_signature, match_key_version, verification_key,
              check_version, relevant_context_hash, compatibility_policy_version)
            VALUES (:tenantId, :candidateId, :canonicalAssetId, :vulnClass, :location,
              :matchKeyVersion, :verificationKey, :checkVersion, :relevantContextHash,
              :compatibilityPolicyVersion)
            ON CONFLICT DO NOTHING
            """)
        .bind("tenantId", context.tenantId())
        .bind("candidateId", UUID.randomUUID())
        .bind("canonicalAssetId", assetId.value())
        .bind("vulnClass", payload.vulnClass())
        .bind("location", payload.normalizedLocationSignature())
        .bind("matchKeyVersion", MATCH_KEY_VERSION)
        .bind("verificationKey", payload.verificationKey())
        .bind("checkVersion", payload.checkVersion())
        .bind("relevantContextHash", contextHash)
        .bind("compatibilityPolicyVersion", payload.compatibilityPolicyVersion())
        .execute();
  }

  private FindingRow readFinding(
      Handle handle,
      TrustedContext context,
      AssetId assetId,
      FindingObservation payload,
      String sourceEventId) {
    return handle
        .createQuery(
            """
            SELECT finding_id, verification_key, check_version, relevant_context_hash,
              compatibility_policy_version
            FROM findings
            WHERE tenant_id = :tenantId AND canonical_asset_id = :canonicalAssetId
              AND vuln_class = :vulnClass AND normalized_location_signature = :location
              AND match_key_version = :matchKeyVersion
            """)
        .bind("tenantId", context.tenantId())
        .bind("canonicalAssetId", assetId.value())
        .bind("vulnClass", payload.vulnClass())
        .bind("location", payload.normalizedLocationSignature())
        .bind("matchKeyVersion", MATCH_KEY_VERSION)
        .map(
            (rs, ctx) ->
                new FindingRow(
                    rs.getObject("finding_id", UUID.class),
                    rs.getString("verification_key"),
                    rs.getString("check_version"),
                    rs.getString("relevant_context_hash"),
                    rs.getInt("compatibility_policy_version")))
        .findOne()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "finding row missing after match insert for source event " + sourceEventId));
  }

  /** All four baseline values must equal the recorded row; drift never updates. */
  private static void requireRecordedBaseline(
      FindingRow recorded,
      FindingObservation payload,
      String contextHash,
      TrustedContext context,
      String sourceEventId) {
    boolean matches =
        recorded.verificationKey().equals(payload.verificationKey())
            && recorded.checkVersion().equals(payload.checkVersion())
            && recorded.relevantContextHash().equals(contextHash)
            && recorded.compatibilityPolicyVersion() == payload.compatibilityPolicyVersion();
    if (!matches) {
      throw new FindingBaselineConflictException(
          ("source event %s for tenant %s: observation disagrees with the recorded verification"
                  + " baseline of finding %s")
              .formatted(sourceEventId, context.tenantId(), recorded.findingId()));
    }
  }

  private void insertOccurrence(
      Handle handle, TrustedContext context, String sourceEventId, UUID findingId) {
    handle
        .createUpdate(
            "INSERT INTO finding_occurrences (tenant_id, source_event_id, finding_id)"
                + " VALUES (:tenantId, :sourceEventId, :findingId)")
        .bind("tenantId", context.tenantId())
        .bind("sourceEventId", sourceEventId)
        .bind("findingId", findingId)
        .execute();
  }

  private record FindingRow(
      UUID findingId,
      String verificationKey,
      String checkVersion,
      String relevantContextHash,
      int compatibilityPolicyVersion) {}
}
