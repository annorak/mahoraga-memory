package dev.mahoraga.memory.planning;

import dev.mahoraga.memory.boundary.BoundaryFactQuery;
import dev.mahoraga.memory.boundary.FinalizedBoundaries;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.fixture.DeploymentTarget;
import dev.mahoraga.memory.posture.PostureFolder;
import dev.mahoraga.memory.posture.PostureResult;
import dev.mahoraga.memory.posture.PostureResult.LastVerifiedExposure;
import dev.mahoraga.memory.posture.SelectedFact;
import dev.mahoraga.memory.posture.SelectedFact.FindingOccurrence;
import dev.mahoraga.memory.posture.SelectedFact.TestAttempt;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jdbi.v3.core.Handle;

/**
 * Derives each candidate's single memory feature from persisted facts visible
 * at an explicit knowledge boundary. Every position must be exactly a
 * write-once finalized limit of the trusted tenant's streams; nothing infers
 * "latest". A candidate's authoritative Deployment key resolves to the
 * recorded canonical asset, its findings are matched by verification key, and
 * the pure fold decides the feature: true only while at least one matching
 * finding's boundary-folded last exposure is still {@code VERIFIED_RESOLVED}.
 * An unknown target or verification key is simply {@code false}; a database
 * failure propagates instead of becoming empty memory.
 */
public final class PreEngagementMemoryQuery {

  private static final String TARGET_ASSET_SQL =
      """
      SELECT target.cluster_id, target.resource_kind, target.resource_uid, a.canonical_asset_id
      FROM unnest(:clusterIds, :resourceKinds, :resourceUids)
        AS target(cluster_id, resource_kind, resource_uid)
      JOIN assets a ON a.tenant_id = :tenantId
        AND a.cluster_id = target.cluster_id
        AND a.resource_kind = target.resource_kind
        AND a.resource_uid = target.resource_uid
      """;

  private final BoundaryFactQuery factQuery = new BoundaryFactQuery();
  private final PostureFolder postureFolder = new PostureFolder();

  @Inject
  public PreEngagementMemoryQuery() {}

  /** One derived feature per candidate, returned in candidate order. */
  public List<MemoryFeature> deriveFeatures(
      Handle handle,
      TrustedContext context,
      KnowledgeBoundary boundary,
      List<CandidateTest> candidates) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(boundary, "boundary");
    List<CandidateTest> distinct = requireDistinctCandidates(candidates);
    FinalizedBoundaries.requireFinalized(handle, context, boundary);
    Set<VerificationTarget> resolvedTargets = resolvedTargetsAt(handle, context, boundary);
    Map<DeploymentTarget, UUID> assetIds = resolveAssetIds(handle, context, distinct);
    List<MemoryFeature> features = new ArrayList<>(distinct.size());
    for (CandidateTest candidate : distinct) {
      UUID assetId = assetIds.get(candidate.target());
      boolean hasPriorVerifiedResolution =
          assetId != null
              && resolvedTargets.contains(
                  new VerificationTarget(assetId, candidate.verificationKey()));
      features.add(new MemoryFeature(candidate.candidateId(), hasPriorVerifiedResolution));
    }
    return List.copyOf(features);
  }

  /**
   * Folds every finding visible at the boundary and keeps the (asset,
   * verification key) pairs whose last exposure is still verified resolved.
   * The fold's exposure dimension is engagement-independent, so the upcoming
   * engagement id from trusted context is only a formal fold argument.
   */
  private Set<VerificationTarget> resolvedTargetsAt(
      Handle handle, TrustedContext context, KnowledgeBoundary boundary) {
    List<SelectedFact> facts = factQuery.selectFacts(handle, context, boundary);
    Map<UUID, List<SelectedFact>> occurrencesByFinding = new LinkedHashMap<>();
    List<SelectedFact> attempts = new ArrayList<>();
    for (SelectedFact fact : facts) {
      switch (fact) {
        case FindingOccurrence occurrence ->
            occurrencesByFinding
                .computeIfAbsent(occurrence.findingId(), findingId -> new ArrayList<>())
                .add(occurrence);
        case TestAttempt attempt -> attempts.add(attempt);
      }
    }
    Set<VerificationTarget> resolved = new HashSet<>();
    for (List<SelectedFact> occurrences : occurrencesByFinding.values()) {
      List<SelectedFact> evidence = new ArrayList<>(occurrences);
      evidence.addAll(attempts);
      PostureResult posture = postureFolder.fold(context.engagementId(), evidence);
      if (posture.lastVerifiedExposure() == LastVerifiedExposure.VERIFIED_RESOLVED) {
        FindingOccurrence identity = (FindingOccurrence) occurrences.get(0);
        resolved.add(
            new VerificationTarget(identity.canonicalAssetId(), identity.verificationKey()));
      }
    }
    return resolved;
  }

  /** One set-based tenant-qualified lookup; targets with no recorded asset stay absent. */
  private static Map<DeploymentTarget, UUID> resolveAssetIds(
      Handle handle, TrustedContext context, List<CandidateTest> candidates) {
    List<DeploymentTarget> targets =
        candidates.stream().map(CandidateTest::target).distinct().toList();
    if (targets.isEmpty()) {
      return Map.of();
    }
    Map<DeploymentTarget, UUID> assetIds = new HashMap<>();
    handle
        .createQuery(TARGET_ASSET_SQL)
        .bind("tenantId", context.tenantId())
        .bindArray(
            "clusterIds", String.class, targets.stream().map(DeploymentTarget::clusterId).toList())
        .bindArray(
            "resourceKinds",
            String.class,
            targets.stream().map(DeploymentTarget::resourceKind).toList())
        .bindArray(
            "resourceUids",
            String.class,
            targets.stream().map(DeploymentTarget::resourceUid).toList())
        .map(
            (rs, ctx) ->
                Map.entry(
                    new DeploymentTarget(
                        rs.getString("cluster_id"),
                        rs.getString("resource_kind"),
                        rs.getString("resource_uid")),
                    rs.getObject("canonical_asset_id", UUID.class)))
        .forEach(entry -> assetIds.put(entry.getKey(), entry.getValue()));
    return assetIds;
  }

  private static List<CandidateTest> requireDistinctCandidates(List<CandidateTest> candidates) {
    List<CandidateTest> copied = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    Set<String> candidateIds = new HashSet<>();
    for (CandidateTest candidate : copied) {
      if (!candidateIds.add(candidate.candidateId())) {
        throw new IllegalArgumentException(
            "memory query names candidate %s more than once".formatted(candidate.candidateId()));
      }
    }
    return copied;
  }

  /** The tenant-safe join key between a candidate and folded finding exposure. */
  private record VerificationTarget(UUID canonicalAssetId, String verificationKey) {}
}
