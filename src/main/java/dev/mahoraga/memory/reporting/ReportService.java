package dev.mahoraga.memory.reporting;

import dev.mahoraga.memory.boundary.BoundaryFactQuery;
import dev.mahoraga.memory.boundary.FinalizedBoundaries;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.boundary.KnowledgeBoundaryCodec;
import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.coverage.CoverageCompatibilityPolicyV1;
import dev.mahoraga.memory.coverage.FindingVerificationBaseline;
import dev.mahoraga.memory.coverage.RecordedTestAttempt;
import dev.mahoraga.memory.posture.PostureFolder;
import dev.mahoraga.memory.posture.PostureResult;
import dev.mahoraga.memory.posture.SelectedFact;
import dev.mahoraga.memory.posture.SelectedFact.FindingOccurrence;
import dev.mahoraga.memory.posture.SelectedFact.TestAttempt;
import dev.mahoraga.memory.reporting.Report.FindingResult;
import dev.mahoraga.memory.reporting.Report.ReportView;
import dev.mahoraga.memory.reporting.SemanticFactSet.AssetKey;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jdbi.v3.core.Handle;

/**
 * Renders one finalized fact set as the strict stateless current-engagement
 * view or the longitudinal memory view. Both views require every requested
 * position to be exactly a write-once finalized boundary of the trusted
 * tenant, share one boundary fact selection, and expose the digest of the
 * identical current-engagement semantic fact subset. Only the memory view
 * invokes the longitudinal fold; the stateless view never inspects prior
 * engagements, which is why a finding with no current fact is not
 * representable there.
 */
public final class ReportService {

  private final BoundaryFactQuery factQuery = new BoundaryFactQuery();
  private final PostureFolder postureFolder = new PostureFolder();

  @Inject
  public ReportService() {}

  /** The current-engagement-only view; the boundary may name nothing else. */
  public Report statelessReport(Handle handle, TrustedContext context, KnowledgeBoundary boundary) {
    Map<String, String> engagementByStream =
        FinalizedBoundaries.requireFinalized(handle, context, boundary);
    for (Map.Entry<String, String> binding : engagementByStream.entrySet()) {
      if (!binding.getValue().equals(context.engagementId())) {
        throw new IllegalArgumentException(
            "the stateless view accepts only the current engagement %s, but stream %s belongs to %s"
                .formatted(context.engagementId(), binding.getKey(), binding.getValue()));
      }
    }
    List<SelectedFact> facts = factQuery.selectFacts(handle, context, boundary);
    Map<UUID, AssetKey> assetKeys = loadAssetKeys(handle, context, facts);
    SemanticFactSet currentFacts = SemanticFactSet.of(facts, assetKeys);
    return report(ReportView.STATELESS, boundary, context, currentFacts, statelessSummary(facts));
  }

  /** The longitudinal view; the boundary must include the current engagement. */
  public Report memoryReport(Handle handle, TrustedContext context, KnowledgeBoundary boundary) {
    Map<String, String> engagementByStream =
        FinalizedBoundaries.requireFinalized(handle, context, boundary);
    if (!engagementByStream.containsValue(context.engagementId())) {
      throw new IllegalArgumentException(
          "the memory view requires the current engagement %s inside its boundary"
              .formatted(context.engagementId()));
    }
    List<SelectedFact> facts = factQuery.selectFacts(handle, context, boundary);
    Map<UUID, AssetKey> assetKeys = loadAssetKeys(handle, context, facts);
    List<SelectedFact> currentSubset =
        facts.stream().filter(fact -> fact.engagementId().equals(context.engagementId())).toList();
    SemanticFactSet currentFacts = SemanticFactSet.of(currentSubset, assetKeys);
    Report.MemorySummary summary = memorySummary(facts, assetKeys, context.engagementId());
    return report(ReportView.MEMORY, boundary, context, currentFacts, summary);
  }

  private static Report report(
      ReportView view,
      KnowledgeBoundary boundary,
      TrustedContext context,
      SemanticFactSet currentFacts,
      Report.Summary summary) {
    String boundaryHash = KnowledgeBoundaryCodec.hash(boundary);
    return new Report(
        view, boundaryHash, context.engagementId(), Report.POLICY_BUNDLE_VERSION,
        currentFacts.digest(), summary);
  }

  private static Map<UUID, AssetKey> loadAssetKeys(
      Handle handle, TrustedContext context, List<SelectedFact> facts) {
    List<UUID> assetIds = facts.stream().map(ReportService::assetIdOf).distinct().toList();
    if (assetIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, AssetKey> keys = new HashMap<>();
    handle
        .createQuery(
            "SELECT canonical_asset_id, cluster_id, resource_kind, resource_uid FROM assets"
                + " WHERE tenant_id = :tenantId AND canonical_asset_id = ANY(:assetIds)")
        .bind("tenantId", context.tenantId())
        .bindArray("assetIds", UUID.class, assetIds)
        .map(
            (rs, ctx) ->
                Map.entry(
                    rs.getObject("canonical_asset_id", UUID.class),
                    new AssetKey(
                        rs.getString("cluster_id"),
                        rs.getString("resource_kind"),
                        rs.getString("resource_uid"))))
        .forEach(entry -> keys.put(entry.getKey(), entry.getValue()));
    if (keys.size() != assetIds.size()) {
      throw new IllegalStateException(
          "a selected fact references an asset with no authoritative key row");
    }
    return keys;
  }

  private static UUID assetIdOf(SelectedFact fact) {
    return switch (fact) {
      case FindingOccurrence occurrence -> occurrence.canonicalAssetId();
      case TestAttempt attempt -> attempt.canonicalAssetId();
    };
  }

  /**
   * Detections come from occurrences alone; a completed detected attempt must
   * pair with a compatible occurrence and adds no count. Standalone completed
   * negatives and incomplete attempts each count exactly once.
   */
  private static Report.StatelessSummary statelessSummary(List<SelectedFact> facts) {
    List<FindingOccurrence> occurrences =
        facts.stream()
            .filter(FindingOccurrence.class::isInstance)
            .map(FindingOccurrence.class::cast)
            .toList();
    int detected = (int) occurrences.stream().map(FindingOccurrence::findingId).distinct().count();
    int notDetected = 0;
    int partial = 0;
    for (SelectedFact fact : facts) {
      if (!(fact instanceof TestAttempt attempt)) {
        continue;
      }
      if (attempt.executionStatus() != ExecutionStatus.COMPLETED) {
        partial++;
      } else if (attempt.result() == TestResult.NOT_DETECTED) {
        notDetected++;
      } else if (attempt.result() == TestResult.DETECTED) {
        requirePairedOccurrence(attempt, occurrences);
      } else {
        throw new IllegalStateException(
            "completed attempt %s carries no representable result".formatted(fact.sourceEventId()));
      }
    }
    return new Report.StatelessSummary(detected, notDetected, partial);
  }

  private static void requirePairedOccurrence(
      TestAttempt attempt, List<FindingOccurrence> occurrences) {
    boolean paired =
        occurrences.stream().anyMatch(occurrence -> isCompatible(attempt, baselineOf(occurrence)));
    if (!paired) {
      throw new IllegalStateException(
          "completed detected attempt %s has no compatible finding occurrence to pair with"
              .formatted(attempt.sourceEventId()));
    }
  }

  /** One fold per semantic finding; every fold sees all attempts and keeps only compatible ones. */
  private Report.MemorySummary memorySummary(
      List<SelectedFact> facts, Map<UUID, AssetKey> assetKeys, String currentEngagementId) {
    Map<UUID, List<SelectedFact>> occurrencesByFinding = new LinkedHashMap<>();
    List<TestAttempt> attempts = new ArrayList<>();
    for (SelectedFact fact : facts) {
      switch (fact) {
        case FindingOccurrence occurrence ->
            occurrencesByFinding
                .computeIfAbsent(occurrence.findingId(), findingId -> new ArrayList<>())
                .add(occurrence);
        case TestAttempt attempt -> attempts.add(attempt);
      }
    }
    requireDetectedAttemptsRepresented(attempts, occurrencesByFinding);
    List<FindingResult> results = new ArrayList<>();
    for (List<SelectedFact> occurrences : occurrencesByFinding.values()) {
      List<SelectedFact> evidence = new ArrayList<>(occurrences);
      evidence.addAll(attempts);
      PostureResult posture = postureFolder.fold(currentEngagementId, evidence);
      results.add(findingResult((FindingOccurrence) occurrences.get(0), assetKeys, posture));
    }
    return new Report.MemorySummary(results);
  }

  /** A detection incompatible with every known finding would silently vanish; fail instead. */
  private static void requireDetectedAttemptsRepresented(
      List<TestAttempt> attempts, Map<UUID, List<SelectedFact>> occurrencesByFinding) {
    List<FindingVerificationBaseline> baselines =
        occurrencesByFinding.values().stream()
            .map(occurrences -> baselineOf((FindingOccurrence) occurrences.get(0)))
            .toList();
    for (TestAttempt attempt : attempts) {
      if (attempt.executionStatus() != ExecutionStatus.COMPLETED
          || attempt.result() != TestResult.DETECTED) {
        continue;
      }
      if (baselines.stream().noneMatch(baseline -> isCompatible(attempt, baseline))) {
        throw new IllegalStateException(
            "completed detected attempt %s matches no finding visible at the boundary"
                .formatted(attempt.sourceEventId()));
      }
    }
  }

  private static FindingResult findingResult(
      FindingOccurrence identity, Map<UUID, AssetKey> assetKeys, PostureResult posture) {
    AssetKey key = assetKeys.get(identity.canonicalAssetId());
    return new FindingResult(
        key.clusterId(),
        key.resourceKind(),
        key.resourceUid(),
        identity.vulnClass(),
        identity.normalizedLocationSignature(),
        identity.matchKeyVersion(),
        identity.verificationKey(),
        posture);
  }

  private static FindingVerificationBaseline baselineOf(FindingOccurrence occurrence) {
    return new FindingVerificationBaseline(
        occurrence.tenantId(),
        occurrence.canonicalAssetId(),
        occurrence.verificationKey(),
        occurrence.checkVersion(),
        occurrence.relevantContextHash(),
        occurrence.compatibilityPolicyVersion());
  }

  private static boolean isCompatible(TestAttempt attempt, FindingVerificationBaseline baseline) {
    return CoverageCompatibilityPolicyV1.isCompatible(
        new RecordedTestAttempt(
            attempt.tenantId(),
            attempt.sourceEventId(),
            attempt.canonicalAssetId(),
            attempt.verificationKey(),
            attempt.checkVersion(),
            attempt.relevantContextHash(),
            attempt.compatibilityPolicyVersion(),
            attempt.executionStatus(),
            attempt.result()),
        baseline);
  }
}
