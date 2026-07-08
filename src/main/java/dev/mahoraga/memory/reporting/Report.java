package dev.mahoraga.memory.reporting;

import dev.mahoraga.memory.posture.PostureResult;
import dev.mahoraga.memory.posture.PostureResult.EpisodeClassification;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The immutable report contract shared by both views: the view discriminator,
 * the tenant-safe knowledge-boundary hash, the current engagement, the fixed
 * policy bundle version, the current-engagement semantic fact digest, and one
 * view-specific summary. No generated timestamp or operational value exists
 * here, so equal semantic inputs always produce an equal report.
 */
public record Report(
    ReportView view,
    String knowledgeBoundaryHash,
    String currentEngagementId,
    int policyBundleVersion,
    String currentEngagementFactDigest,
    Summary summary) {

  /** The one classification/coverage policy bundle the MVP reports under. */
  public static final int POLICY_BUNDLE_VERSION = 1;

  public Report {
    Objects.requireNonNull(view, "view");
    requireNonBlank(knowledgeBoundaryHash, "knowledgeBoundaryHash");
    requireNonBlank(currentEngagementId, "currentEngagementId");
    requireNonBlank(currentEngagementFactDigest, "currentEngagementFactDigest");
    Objects.requireNonNull(summary, "summary");
    if (policyBundleVersion != POLICY_BUNDLE_VERSION) {
      throw new IllegalArgumentException(
          "reports are rendered under policy bundle version %d, not %d"
              .formatted(POLICY_BUNDLE_VERSION, policyBundleVersion));
    }
    boolean statelessPair = view == ReportView.STATELESS && summary instanceof StatelessSummary;
    boolean memoryPair = view == ReportView.MEMORY && summary instanceof MemorySummary;
    if (!statelessPair && !memoryPair) {
      throw new IllegalArgumentException(
          "report view %s does not match summary type %s"
              .formatted(view, summary.getClass().getSimpleName()));
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("a report requires a nonblank " + field);
    }
  }

  /** Which knowledge scope produced the report; the fact selection is shared. */
  public enum ReportView {
    STATELESS,
    MEMORY
  }

  public sealed interface Summary permits StatelessSummary, MemorySummary {}

  /**
   * The strict current-engagement summary. It carries counts only: without
   * prior-engagement scope there is no subject roster, so findings with no
   * current fact are not representable and no longitudinal classification can
   * exist.
   */
  public record StatelessSummary(int detectedCount, int notDetectedCount, int partialCount)
      implements Summary {

    public StatelessSummary {
      requireNonNegative(detectedCount, "detectedCount");
      requireNonNegative(notDetectedCount, "notDetectedCount");
      requireNonNegative(partialCount, "partialCount");
    }

    private static void requireNonNegative(int count, String field) {
      if (count < 0) {
        throw new IllegalArgumentException("a stateless summary requires a non-negative " + field);
      }
    }
  }

  /**
   * The longitudinal summary: one folded result per semantic finding, sorted
   * by stable finding identity so rendering order never depends on database
   * or arrival order. Classification counts derive from the results; they are
   * not stored separately.
   */
  public record MemorySummary(List<FindingResult> findings) implements Summary {

    /** The fixed order longitudinal classifications are reported in. */
    public static final List<EpisodeClassification> CLASSIFICATION_REPORT_ORDER =
        List.of(
            EpisodeClassification.NEW,
            EpisodeClassification.STILL_OPEN,
            EpisodeClassification.VERIFIED_RESOLVED,
            EpisodeClassification.REGRESSED,
            EpisodeClassification.NOT_RETESTED,
            EpisodeClassification.INCONCLUSIVE);

    private static final Comparator<FindingResult> FINDING_ORDER =
        Comparator.comparing(FindingResult::clusterId)
            .thenComparing(FindingResult::resourceKind)
            .thenComparing(FindingResult::resourceUid)
            .thenComparing(FindingResult::vulnClass)
            .thenComparing(FindingResult::normalizedLocationSignature)
            .thenComparingInt(FindingResult::matchKeyVersion)
            .thenComparing(FindingResult::verificationKey);

    public MemorySummary {
      Objects.requireNonNull(findings, "findings");
      List<FindingResult> sorted = new ArrayList<>(findings);
      if (sorted.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("a memory summary rejects null finding results");
      }
      sorted.sort(FINDING_ORDER);
      for (int i = 1; i < sorted.size(); i++) {
        if (FINDING_ORDER.compare(sorted.get(i - 1), sorted.get(i)) == 0) {
          throw new IllegalArgumentException(
              "duplicate finding result for verification key "
                  + sorted.get(i).verificationKey());
        }
      }
      findings = List.copyOf(sorted);
    }

    public long classificationCount(EpisodeClassification classification) {
      Objects.requireNonNull(classification, "classification");
      return findings.stream()
          .filter(result -> result.posture().episodeClassification() == classification)
          .count();
    }
  }

  /**
   * One semantic finding's longitudinal result: its stable identity components
   * plus the three separate posture dimensions from the posture fold.
   */
  public record FindingResult(
      String clusterId,
      String resourceKind,
      String resourceUid,
      String vulnClass,
      String normalizedLocationSignature,
      int matchKeyVersion,
      String verificationKey,
      PostureResult posture) {

    public FindingResult {
      requireNonBlank(clusterId, "clusterId");
      requireNonBlank(resourceKind, "resourceKind");
      requireNonBlank(resourceUid, "resourceUid");
      requireNonBlank(vulnClass, "vulnClass");
      requireNonBlank(normalizedLocationSignature, "normalizedLocationSignature");
      requireNonBlank(verificationKey, "verificationKey");
      Objects.requireNonNull(posture, "posture");
    }

    private static void requireNonBlank(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("a finding result requires a nonblank " + field);
      }
    }
  }
}
