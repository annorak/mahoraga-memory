package dev.mahoraga.memory.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalEncoding;
import dev.mahoraga.memory.posture.PostureFolder;
import dev.mahoraga.memory.posture.SelectedFact;
import dev.mahoraga.memory.posture.SelectedFact.FindingOccurrence;
import dev.mahoraga.memory.posture.SelectedFact.TestAttempt;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical semantic representation of one boundary-selected fact subset, and
 * its lowercase SHA-256 digest over compact fixed-order UTF-8 JSON. Internal
 * random UUIDs are replaced by the stable identity they stand for: the
 * authoritative Deployment key for assets, and the match components already on
 * each occurrence for findings. Facts sort by the TASK-009 domain order with
 * fact kind as the final tie-break, so caller order, arrival order, and
 * restart can never change the bytes. Operational timestamps, trace data, and
 * fixture vocabulary are structurally absent from the input facts.
 */
public final class SemanticFactSet {

  /** Domain chronology first; occurrences sort before attempts on a full tie. */
  private static final Comparator<SelectedFact> SEMANTIC_ORDER =
      PostureFolder.DOMAIN_ORDER.thenComparingInt(SemanticFactSet::factKindRank);

  private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

  private final String canonicalJson;
  private final String digest;

  private SemanticFactSet(String canonicalJson, String digest) {
    this.canonicalJson = canonicalJson;
    this.digest = digest;
  }

  /**
   * Builds the canonical set. Every fact's internal asset id must resolve to
   * an authoritative key, and two facts may never canonicalize to the same
   * semantic row; both defects fail loudly instead of being dropped.
   */
  public static SemanticFactSet of(List<SelectedFact> facts, Map<UUID, AssetKey> assetKeys) {
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(assetKeys, "assetKeys");
    List<Map<String, Object>> canonicalFacts = new ArrayList<>(facts.size());
    Set<Map<String, Object>> seen = new HashSet<>();
    for (SelectedFact fact : facts.stream().sorted(SEMANTIC_ORDER).toList()) {
      Map<String, Object> canonical = canonicalFact(fact, assetKeys);
      if (!seen.add(canonical)) {
        throw new IllegalArgumentException(
            "duplicate semantic fact for source event " + fact.sourceEventId());
      }
      canonicalFacts.add(canonical);
    }
    String json = writeCanonicalJson(canonicalFacts);
    return new SemanticFactSet(
        json, CanonicalEncoding.sha256Hex(json.getBytes(StandardCharsets.UTF_8)));
  }

  public String canonicalJson() {
    return canonicalJson;
  }

  public String digest() {
    return digest;
  }

  private static Map<String, Object> canonicalFact(SelectedFact fact, Map<UUID, AssetKey> keys) {
    return switch (fact) {
      case FindingOccurrence occurrence ->
          occurrenceFields(occurrence, requireAssetKey(occurrence.canonicalAssetId(), keys, fact));
      case TestAttempt attempt ->
          attemptFields(attempt, requireAssetKey(attempt.canonicalAssetId(), keys, fact));
    };
  }

  private static Map<String, Object> occurrenceFields(FindingOccurrence occurrence, AssetKey key) {
    Map<String, Object> fields = commonFields("finding_occurrence", occurrence, key);
    fields.put("vuln_class", occurrence.vulnClass());
    fields.put("normalized_location_signature", occurrence.normalizedLocationSignature());
    fields.put("match_key_version", occurrence.matchKeyVersion());
    fields.put("verification_key", occurrence.verificationKey());
    fields.put("check_version", occurrence.checkVersion());
    fields.put("relevant_context_hash", occurrence.relevantContextHash());
    fields.put("compatibility_policy_version", occurrence.compatibilityPolicyVersion());
    return fields;
  }

  private static Map<String, Object> attemptFields(TestAttempt attempt, AssetKey key) {
    Map<String, Object> fields = commonFields("test_attempt", attempt, key);
    fields.put("verification_key", attempt.verificationKey());
    fields.put("check_version", attempt.checkVersion());
    fields.put("relevant_context_hash", attempt.relevantContextHash());
    fields.put("compatibility_policy_version", attempt.compatibilityPolicyVersion());
    fields.put("execution_status", attempt.executionStatus().wireValue());
    if (attempt.result() != null) {
      fields.put("result", attempt.result().wireValue());
    }
    return fields;
  }

  private static Map<String, Object> commonFields(
      String factKind, SelectedFact fact, AssetKey key) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("fact_kind", factKind);
    fields.put("source_event_id", fact.sourceEventId());
    fields.put("source_stream_id", fact.sourceStreamId());
    fields.put("source_sequence", fact.sourceSequence());
    fields.put("occurred_at", DateTimeFormatter.ISO_INSTANT.format(fact.occurredAt()));
    fields.put("cluster_id", key.clusterId());
    fields.put("resource_kind", key.resourceKind());
    fields.put("resource_uid", key.resourceUid());
    return fields;
  }

  private static AssetKey requireAssetKey(
      UUID canonicalAssetId, Map<UUID, AssetKey> keys, SelectedFact fact) {
    AssetKey key = keys.get(canonicalAssetId);
    if (key == null) {
      throw new IllegalArgumentException(
          "no authoritative asset key was supplied for source event " + fact.sourceEventId());
    }
    return key;
  }

  private static int factKindRank(SelectedFact fact) {
    return fact instanceof FindingOccurrence ? 0 : 1;
  }

  private static String writeCanonicalJson(List<Map<String, Object>> canonicalFacts) {
    try {
      return CANONICAL_MAPPER.writeValueAsString(Map.of("facts", canonicalFacts));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("validated semantic facts failed to serialize", e);
    }
  }

  /** The stable authoritative Deployment identity substituted for internal asset UUIDs. */
  public record AssetKey(String clusterId, String resourceKind, String resourceUid) {

    public AssetKey {
      requireNonBlank(clusterId, "clusterId");
      requireNonBlank(resourceKind, "resourceKind");
      requireNonBlank(resourceUid, "resourceUid");
    }

    private static void requireNonBlank(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("asset key requires a nonblank " + field);
      }
    }
  }
}
