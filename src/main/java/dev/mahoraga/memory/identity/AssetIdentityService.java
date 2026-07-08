package dev.mahoraga.memory.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.contract.SourcePayload.AssetObservation;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.ingest.IngestionFaultHook;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Handle;

/**
 * Canonical Deployment identity under resolution policy version 1: an
 * authoritative {@code (tenant, cluster, Deployment, resource UID)} key creates
 * or reuses exactly one recorded asset; UID-less observations that collide with
 * confirmed assets on exact DNS or an exact label pair are held as ambiguous
 * with no canonical asset; anything else is rejected. Both operations use the
 * caller's active ingestion handle and never open their own transaction.
 */
public final class AssetIdentityService {

  public static final int RESOLUTION_POLICY_VERSION = 1;
  public static final String RESOLVED_OUTCOME = "RESOLVED";
  public static final String AMBIGUOUS_OUTCOME = "AMBIGUOUS";
  public static final String AUTHORITATIVE_BASIS = "AUTHORITATIVE_DEPLOYMENT_KEY";
  public static final String WEAK_COLLISION_BASIS = "WEAK_SIGNAL_COLLISION";

  private static final String MVP_RESOURCE_KIND = SourceEventValidator.MVP_RESOURCE_KIND;

  private final ObjectMapper objectMapper;
  private final IngestionFaultHook faultHook;

  @Inject
  public AssetIdentityService(ObjectMapper objectMapper, IngestionFaultHook faultHook) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.faultHook = Objects.requireNonNull(faultHook, "faultHook");
  }

  /**
   * Insert-or-read of the confirmed canonical asset for an authoritative
   * Deployment key. The candidate row loses any uniqueness race silently and
   * the recorded winner is read back, so callers never see a losing UUID.
   * Finding and test-attempt handlers reuse this method because their payloads
   * carry the same key.
   */
  public AssetId resolveAuthoritativeDeployment(
      Handle handle,
      TrustedContext context,
      String sourceEventId,
      String clusterId,
      String resourceUid) {
    handle
        .createUpdate(
            "INSERT INTO assets (tenant_id, canonical_asset_id, cluster_id, resource_kind,"
                + " resource_uid) VALUES (:tenantId, :candidateId, :clusterId, :resourceKind,"
                + " :resourceUid) ON CONFLICT DO NOTHING")
        .bind("tenantId", context.tenantId())
        .bind("candidateId", UUID.randomUUID())
        .bind("clusterId", clusterId)
        .bind("resourceKind", MVP_RESOURCE_KIND)
        .bind("resourceUid", resourceUid)
        .execute();
    UUID recorded =
        handle
            .createQuery(
                "SELECT canonical_asset_id FROM assets WHERE tenant_id = :tenantId"
                    + " AND cluster_id = :clusterId AND resource_kind = :resourceKind"
                    + " AND resource_uid = :resourceUid")
            .bind("tenantId", context.tenantId())
            .bind("clusterId", clusterId)
            .bind("resourceKind", MVP_RESOURCE_KIND)
            .bind("resourceUid", resourceUid)
            .mapTo(UUID.class)
            .findOne()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "asset row missing after authoritative insert for source event "
                            + sourceEventId));
    faultHook.afterStage(IngestionFaultHook.Stage.AFTER_CANONICAL_ASSET_RESOLUTION, handle);
    return new AssetId(recorded);
  }

  /**
   * Records exactly one observation row for an accepted asset-observation
   * event. The source row already exists in the same transaction and remains
   * the observation's identity and chronology parent.
   */
  public AssetResolution recordAssetObservation(
      Handle handle, TrustedContext context, String sourceEventId, AssetObservation payload) {
    if (payload.resourceUid() != null) {
      AssetId assetId =
          resolveAuthoritativeDeployment(
              handle, context, sourceEventId, payload.clusterId(), payload.resourceUid());
      insertObservation(handle, context, sourceEventId, payload, assetId.value(),
          RESOLVED_OUTCOME, AUTHORITATIVE_BASIS);
      return new AssetResolution.Resolved(assetId);
    }
    if (!hasConfirmedWeakCandidate(handle, context, payload)) {
      throw new UnsupportedAssetIdentityException(
          ("source event %s for tenant %s: no authoritative resource UID and no confirmed weak"
                  + " candidate; provisional assets are unsupported in resolution policy version "
                  + RESOLUTION_POLICY_VERSION)
              .formatted(sourceEventId, context.tenantId()));
    }
    insertObservation(handle, context, sourceEventId, payload, null,
        AMBIGUOUS_OUTCOME, WEAK_COLLISION_BASIS);
    return new AssetResolution.Ambiguous();
  }

  /**
   * Exact tenant-local weak match against prior RESOLVED observations in the
   * same cluster and kind: DNS equality or at least one exact label key/value
   * pair. Any match makes the observation ambiguous; candidates are never
   * ranked or selected.
   */
  private boolean hasConfirmedWeakCandidate(
      Handle handle, TrustedContext context, AssetObservation payload) {
    return handle
        .createQuery(
            """
            SELECT EXISTS (
              SELECT 1 FROM asset_observations
              WHERE tenant_id = :tenantId
                AND cluster_id = :clusterId
                AND resource_kind = :resourceKind
                AND resolution_outcome = 'RESOLVED'
                AND ((CAST(:dns AS text) IS NOT NULL AND dns = :dns)
                  OR EXISTS (
                    SELECT 1
                    FROM jsonb_each_text(labels) candidate(key, value)
                    JOIN jsonb_each_text(CAST(:labels AS jsonb)) observed(key, value)
                      ON candidate.key = observed.key AND candidate.value = observed.value)))
            """)
        .bind("tenantId", context.tenantId())
        .bind("clusterId", payload.clusterId())
        .bind("resourceKind", MVP_RESOURCE_KIND)
        .bind("dns", payload.dns())
        .bind("labels", labelsJson(payload))
        .mapTo(Boolean.class)
        .one();
  }

  private void insertObservation(
      Handle handle,
      TrustedContext context,
      String sourceEventId,
      AssetObservation payload,
      UUID canonicalAssetId,
      String resolutionOutcome,
      String resolutionBasis) {
    handle
        .createUpdate(
            """
            INSERT INTO asset_observations (tenant_id, source_event_id, canonical_asset_id,
              cluster_id, resource_kind, resource_uid, pod_uid, pod_name, ip_address, dns,
              labels, banner, resolution_outcome, resolution_policy_version, resolution_basis)
            VALUES (:tenantId, :sourceEventId, CAST(:canonicalAssetId AS uuid), :clusterId,
              :resourceKind, :resourceUid, :podUid, :podName, :ipAddress, :dns,
              CAST(:labels AS jsonb), :banner, :resolutionOutcome, :resolutionPolicyVersion,
              :resolutionBasis)
            """)
        .bind("tenantId", context.tenantId())
        .bind("sourceEventId", sourceEventId)
        .bindByType("canonicalAssetId", canonicalAssetId, UUID.class)
        .bind("clusterId", payload.clusterId())
        .bind("resourceKind", MVP_RESOURCE_KIND)
        .bind("resourceUid", payload.resourceUid())
        .bind("podUid", payload.podUid())
        .bind("podName", payload.podName())
        .bind("ipAddress", payload.ipAddress())
        .bind("dns", payload.dns())
        .bind("labels", labelsJson(payload))
        .bind("banner", payload.banner())
        .bind("resolutionOutcome", resolutionOutcome)
        .bind("resolutionPolicyVersion", RESOLUTION_POLICY_VERSION)
        .bind("resolutionBasis", resolutionBasis)
        .execute();
    faultHook.afterStage(IngestionFaultHook.Stage.AFTER_ASSET_OBSERVATION_WRITE, handle);
  }

  private String labelsJson(AssetObservation payload) {
    if (payload.labels() == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(payload.labels());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("validated labels failed to serialize", e);
    }
  }
}
