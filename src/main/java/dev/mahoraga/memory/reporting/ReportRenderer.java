package dev.mahoraga.memory.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalEncoding;
import dev.mahoraga.memory.posture.PostureResult.EpisodeClassification;
import dev.mahoraga.memory.reporting.Report.FindingResult;
import dev.mahoraga.memory.reporting.Report.MemorySummary;
import dev.mahoraga.memory.reporting.Report.StatelessSummary;
import dev.mahoraga.memory.reporting.Report.Summary;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two renderings of one report: compact fixed-order canonical UTF-8
 * JSON for hashes and evidence, and concise human text for the demo. The
 * report semantic digest is lowercase SHA-256 of the canonical JSON, so it
 * differs between views while the shared current-engagement fact digest stays
 * equal. Both renderings carry the same semantic values and nothing
 * operational or random.
 */
public final class ReportRenderer {

  private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

  private ReportRenderer() {}

  public static String canonicalJson(Report report) {
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("report_view", report.view().name());
    canonical.put("knowledge_boundary_hash", report.knowledgeBoundaryHash());
    canonical.put("current_engagement_id", report.currentEngagementId());
    canonical.put("policy_bundle_version", report.policyBundleVersion());
    canonical.put("current_engagement_fact_digest", report.currentEngagementFactDigest());
    canonical.put("summary", summaryFields(report.summary()));
    try {
      return CANONICAL_MAPPER.writeValueAsString(canonical);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("validated report failed to serialize", e);
    }
  }

  public static String semanticDigest(Report report) {
    return CanonicalEncoding.sha256Hex(canonicalJson(report).getBytes(StandardCharsets.UTF_8));
  }

  public static String humanText(Report report) {
    StringBuilder text = new StringBuilder();
    text.append("Report view: ").append(report.view().name()).append('\n');
    text.append("Current engagement: ").append(report.currentEngagementId()).append('\n');
    text.append("Knowledge boundary hash: ").append(report.knowledgeBoundaryHash()).append('\n');
    text.append("Current engagement fact digest: ")
        .append(report.currentEngagementFactDigest())
        .append('\n');
    text.append("Policy bundle version: ").append(report.policyBundleVersion()).append('\n');
    switch (report.summary()) {
      case StatelessSummary summary -> appendStatelessText(text, report, summary);
      case MemorySummary summary -> appendMemoryText(text, summary);
    }
    return text.toString();
  }

  private static Map<String, Object> summaryFields(Summary summary) {
    return switch (summary) {
      case StatelessSummary stateless -> statelessFields(stateless);
      case MemorySummary memory -> memoryFields(memory);
    };
  }

  private static Map<String, Object> statelessFields(StatelessSummary summary) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("detected", summary.detectedCount());
    fields.put("not_detected", summary.notDetectedCount());
    fields.put("partial", summary.partialCount());
    return fields;
  }

  private static Map<String, Object> memoryFields(MemorySummary summary) {
    Map<String, Object> counts = new LinkedHashMap<>();
    for (EpisodeClassification classification : MemorySummary.CLASSIFICATION_REPORT_ORDER) {
      counts.put(classification.name(), summary.classificationCount(classification));
    }
    List<Map<String, Object>> findings =
        summary.findings().stream().map(ReportRenderer::findingFields).toList();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("classification_counts", counts);
    fields.put("findings", findings);
    return fields;
  }

  private static Map<String, Object> findingFields(FindingResult result) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("cluster_id", result.clusterId());
    fields.put("resource_kind", result.resourceKind());
    fields.put("resource_uid", result.resourceUid());
    fields.put("vuln_class", result.vulnClass());
    fields.put("normalized_location_signature", result.normalizedLocationSignature());
    fields.put("match_key_version", result.matchKeyVersion());
    fields.put("verification_key", result.verificationKey());
    fields.put("episode_classification", result.posture().episodeClassification().name());
    fields.put("current_assessment", result.posture().currentAssessment().name());
    fields.put("last_verified_exposure", result.posture().lastVerifiedExposure().name());
    return fields;
  }

  private static void appendStatelessText(
      StringBuilder text, Report report, StatelessSummary summary) {
    text.append("Detected: ").append(summary.detectedCount()).append('\n');
    text.append("Not detected: ").append(summary.notDetectedCount()).append('\n');
    text.append("Partial: ").append(summary.partialCount()).append('\n');
    text.append("Findings with no ")
        .append(report.currentEngagementId())
        .append(" fact: not representable\n");
    text.append("Longitudinal classifications: unavailable\n");
  }

  private static void appendMemoryText(StringBuilder text, MemorySummary summary) {
    for (EpisodeClassification classification : MemorySummary.CLASSIFICATION_REPORT_ORDER) {
      text.append(classification.name())
          .append(": ")
          .append(summary.classificationCount(classification))
          .append('\n');
    }
    for (FindingResult result : summary.findings()) {
      text.append(findingLine(result)).append('\n');
    }
  }

  private static String findingLine(FindingResult result) {
    return "%s [%s @ %s, %s/%s/%s]: episode %s, current %s, last verified exposure %s"
        .formatted(
            result.verificationKey(),
            result.vulnClass(),
            result.normalizedLocationSignature(),
            result.clusterId(),
            result.resourceKind(),
            result.resourceUid(),
            result.posture().episodeClassification().name(),
            result.posture().currentAssessment().name(),
            result.posture().lastVerifiedExposure().name());
  }
}
