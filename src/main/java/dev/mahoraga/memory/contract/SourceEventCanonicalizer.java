package dev.mahoraga.memory.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.SourcePayload.AssetObservation;
import dev.mahoraga.memory.contract.SourcePayload.EngagementCompleted;
import dev.mahoraga.memory.contract.SourcePayload.FindingObservation;
import dev.mahoraga.memory.contract.SourcePayload.TestAttempt;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonicalization version 1, owned by schema version 1 and immutable: UTF-8,
 * no insignificant whitespace, the fixed envelope field order, recursively
 * sorted payload keys, canonical UTC instants, and omitted null optionals.
 * Changing these rules requires schema version 2 and new golden vectors.
 */
final class SourceEventCanonicalizer {

  private final ObjectMapper canonicalMapper;

  SourceEventCanonicalizer(ObjectMapper objectMapper) {
    this.canonicalMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
  }

  byte[] canonicalBytes(SourceEvent event) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("source_event_id", event.sourceEventId());
    envelope.put("event_type", event.eventType().wireValue());
    envelope.put("source_stream_id", event.sourceStreamId());
    envelope.put("source_sequence", event.sourceSequence());
    envelope.put("schema_version", event.schemaVersion());
    envelope.put("occurred_at", DateTimeFormatter.ISO_INSTANT.format(event.occurredAt()));
    envelope.put("payload", payloadMap(event.payload()));
    try {
      return canonicalMapper.writeValueAsBytes(envelope);
    } catch (JsonProcessingException e) {
      throw new InvalidSourceEventException(
          "canonicalization failed for source event " + event.sourceEventId(), e);
    }
  }

  /** The canonical payload subtree alone; storage persists it verbatim. */
  String canonicalPayloadJson(SourceEvent event) {
    try {
      return canonicalMapper.writeValueAsString(payloadMap(event.payload()));
    } catch (JsonProcessingException e) {
      throw new InvalidSourceEventException(
          "canonicalization failed for source event " + event.sourceEventId(), e);
    }
  }

  private Map<String, Object> payloadMap(SourcePayload payload) {
    return switch (payload) {
      case AssetObservation asset -> assetMap(asset);
      case FindingObservation finding -> findingMap(finding);
      case TestAttempt attempt -> attemptMap(attempt);
      case EngagementCompleted completion -> completionMap(completion);
    };
  }

  private Map<String, Object> assetMap(AssetObservation payload) {
    Map<String, Object> map = new TreeMap<>();
    putIfPresent(map, "cluster_id", payload.clusterId());
    putIfPresent(map, "resource_kind", payload.resourceKind());
    putIfPresent(map, "resource_uid", payload.resourceUid());
    putIfPresent(map, "pod_uid", payload.podUid());
    putIfPresent(map, "pod_name", payload.podName());
    putIfPresent(map, "ip_address", payload.ipAddress());
    putIfPresent(map, "dns", payload.dns());
    putIfPresent(map, "banner", payload.banner());
    if (payload.labels() != null) {
      map.put("labels", new TreeMap<>(payload.labels()));
    }
    return map;
  }

  private Map<String, Object> findingMap(FindingObservation payload) {
    Map<String, Object> map = new TreeMap<>();
    map.put("cluster_id", payload.clusterId());
    map.put("resource_kind", payload.resourceKind());
    map.put("resource_uid", payload.resourceUid());
    map.put("vuln_class", payload.vulnClass());
    map.put("normalized_location_signature", payload.normalizedLocationSignature());
    map.put("verification_key", payload.verificationKey());
    map.put("check_version", payload.checkVersion());
    map.put("relevant_context", contextMap(payload.relevantContext()));
    map.put("compatibility_policy_version", payload.compatibilityPolicyVersion());
    return map;
  }

  private Map<String, Object> attemptMap(TestAttempt payload) {
    Map<String, Object> map = new TreeMap<>();
    map.put("cluster_id", payload.clusterId());
    map.put("resource_kind", payload.resourceKind());
    map.put("resource_uid", payload.resourceUid());
    map.put("verification_key", payload.verificationKey());
    map.put("check_version", payload.checkVersion());
    map.put("relevant_context", contextMap(payload.relevantContext()));
    map.put("execution_status", payload.executionStatus().wireValue());
    if (payload.result() != null) {
      map.put("result", payload.result().wireValue());
    }
    map.put("compatibility_policy_version", payload.compatibilityPolicyVersion());
    return map;
  }

  private Map<String, Object> completionMap(EngagementCompleted payload) {
    Map<String, Object> map = new TreeMap<>();
    map.put("last_data_sequence", payload.lastDataSequence());
    return map;
  }

  private Map<String, Object> contextMap(RelevantContext context) {
    Map<String, Object> map = new TreeMap<>();
    map.put("protocol", context.protocol());
    map.put("port", context.port());
    map.put("normalized_route", context.normalizedRoute());
    if (context.parameters() != null) {
      map.put("parameters", CanonicalEncoding.canonicalParameters(context.parameters()));
    }
    putIfPresent(map, "target_address", context.targetAddress());
    map.put("is_address_bound", context.isAddressBound());
    return map;
  }

  private static void putIfPresent(Map<String, Object> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
