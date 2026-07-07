package dev.mahoraga.memory.contract;

import static dev.mahoraga.memory.contract.ContractTestSupport.codec;
import static dev.mahoraga.memory.contract.ContractTestSupport.contract;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.dropwizard.validation.BaseValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalSourceHashTest {

  private static final String ASSET_HASH =
      "79c8f9724a82914b5591dbadccc90feba9caa7c10a24b339cbce7665f8112183";
  private static final String FINDING_HASH =
      "d2d9c8209aa8f4c4491de84a80073f09089a75799203d9f0f428feb4a9f784ce";
  private static final String ATTEMPT_HASH =
      "4ac620af3487d93d623a9cd6249cf86c52ae5c4a497148124f195e3af52fcaf7";
  private static final String COMPLETION_HASH =
      "1eec41eb77e9f90b4384515e9a86b54822a8d91edb383b5dbbf1a5c301a47be5";

  private final SourceEventCodec codec = codec();

  @Test
  void matchesGoldenCanonicalJsonAndHashes() {
    assertGolden("asset-observation", ASSET_HASH);
    assertGolden("finding-observation", FINDING_HASH);
    assertGolden("test-attempt", ATTEMPT_HASH);
    assertGolden("engagement-completed", COMPLETION_HASH);
  }

  private void assertGolden(String name, String expectedHash) {
    CanonicalSourceEvent canonical = codec.decode(contract(name + ".json"));
    assertEquals(
        contract(name + ".canonical.json").strip(),
        new String(canonical.canonicalJson(), UTF_8),
        name);
    assertEquals(expectedHash, canonical.canonicalSourceHash(), name);
  }

  @Test
  void canonicalizationDoesNotInheritApplicationSerializationSettings() {
    ObjectMapper configuredMapper =
        new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    SourceEventCodec configuredCodec =
        new SourceEventCodec(
            configuredMapper, new SourceEventValidator(BaseValidator.newValidator()));

    CanonicalSourceEvent expected = codec.decode(contract("finding-observation.json"));
    CanonicalSourceEvent actual =
        configuredCodec.decode(contract("finding-observation.json"));

    assertArrayEquals(expected.canonicalJson(), actual.canonicalJson());
    assertEquals(expected.canonicalPayloadJson(), actual.canonicalPayloadJson());
    assertEquals(FINDING_HASH, actual.canonicalSourceHash());
  }

  @Test
  void hashIsLowercaseHexSha256AndRepeatable() {
    String json = contract("asset-observation.json");
    String first = codec.decode(json).canonicalSourceHash();
    assertTrue(first.matches("[0-9a-f]{64}"));
    assertEquals(first, codec.decode(json).canonicalSourceHash());
  }

  @Test
  void canonicalEnvelopeHasExactlyTheSevenContractFieldsInOrder() throws Exception {
    JsonNode canonical =
        new ObjectMapper().readTree(codec.decode(contract("asset-observation.json")).canonicalJson());
    List<String> fields = new ArrayList<>();
    canonical.fieldNames().forEachRemaining(fields::add);
    // Exact field set and order: no trusted context, producer hash, or
    // ingestion metadata can contribute to the canonical bytes.
    assertEquals(
        List.of(
            "source_event_id",
            "event_type",
            "source_stream_id",
            "source_sequence",
            "schema_version",
            "occurred_at",
            "payload"),
        fields);
  }

  @Test
  void equivalentInputsCanonicalizeIdentically() {
    String base = contract("engagement-completed.json");
    String reordered =
        """
        {"payload":{"last_data_sequence":3},"occurred_at":"2026-01-01T12:00:00Z",
         "schema_version":1,"source_sequence":4,"source_stream_id":"stream-alpha",
         "event_type":"engagement_completed","source_event_id":"evt-complete-001"}
        """;
    assertEquals(hashOf(base), hashOf(reordered));

    assertEquals(
        hashOf(base),
        hashOf(base.replace("2026-01-01T12:00:00Z", "2026-01-01T13:00:00+01:00")),
        "equivalent offsets must canonicalize to the same UTC instant");

    String weakAsset = weakAssetJson("");
    assertEquals(
        hashOf(weakAsset),
        hashOf(weakAssetJson("\"resource_uid\":null,")),
        "explicit null and omitted optional fields are equivalent");

    String finding = contract("finding-observation.json");
    assertEquals(
        hashOf(finding),
        hashOf(finding.replace("\"depth\": 2", "\"depth\": 2.0")),
        "equivalent numeric representations share one canonical form");
  }

  @Test
  void mapInsertionOrderDoesNotAffectTheHash() {
    String labelsReordered =
        contract("asset-observation.json")
            .replace(
                "\"app\": \"api\",\n      \"tier\": \"backend\"",
                "\"tier\": \"backend\",\n      \"app\": \"api\"");
    assertEquals(hashOf(contract("asset-observation.json")), hashOf(labelsReordered));

    String finding = contract("finding-observation.json");
    String parametersReordered =
        finding.replace(
            "\"depth\": 2,\n        \"follow_redirects\": true,\n        \"mode\": \"safe\"",
            "\"mode\": \"safe\",\n        \"depth\": 2,\n        \"follow_redirects\": true");
    assertNotEquals(finding, parametersReordered, "parameter-order mutation must change input");
    assertEquals(hashOf(finding), hashOf(parametersReordered));
  }

  @Test
  void everySemanticFieldChangesTheHash() {
    assertMutationChangesHash("asset-observation.json", "\"source_event_id\": \"evt-asset-001\"",
        "\"source_event_id\": \"evt-asset-002\"");
    assertMutationChangesHash("asset-observation.json", "\"source_stream_id\": \"stream-alpha\"",
        "\"source_stream_id\": \"stream-beta\"");
    assertMutationChangesHash("asset-observation.json", "\"source_sequence\": 1", "\"source_sequence\": 9");
    assertMutationChangesHash("asset-observation.json", "\"occurred_at\": \"2026-01-01T10:00:00Z\"",
        "\"occurred_at\": \"2026-01-01T10:00:01Z\"");
    assertMutationChangesHash("asset-observation.json", "\"cluster_id\": \"cluster-demo\"",
        "\"cluster_id\": \"cluster-other\"");
    assertMutationChangesHash("asset-observation.json", "\"resource_uid\": \"deploy-uid-123\"",
        "\"resource_uid\": \"deploy-uid-124\"");
    assertMutationChangesHash("asset-observation.json", "\"pod_uid\": \"pod-uid-abc\"",
        "\"pod_uid\": \"pod-uid-xyz\"");
    assertMutationChangesHash("asset-observation.json", "\"pod_name\": \"api-7d9f-abc\"",
        "\"pod_name\": \"api-7d9f-xyz\"");
    assertMutationChangesHash("asset-observation.json", "\"ip_address\": \"10.0.0.10\"",
        "\"ip_address\": \"10.0.0.42\"");
    assertMutationChangesHash("asset-observation.json", "\"dns\": \"api.demo.svc.cluster.local\"",
        "\"dns\": \"api2.demo.svc.cluster.local\"");
    assertMutationChangesHash("asset-observation.json", "\"app\": \"api\"", "\"app\": \"api2\"");
    assertMutationChangesHash("asset-observation.json", "\"banner\": \"nginx/1.25\"",
        "\"banner\": \"nginx/1.26\"");
  }

  @Test
  void everyFindingAndAttemptFieldChangesTheHash() {
    assertMutationChangesHash("finding-observation.json", "\"vuln_class\": \"sql_injection\"",
        "\"vuln_class\": \"xss\"");
    assertMutationChangesHash("finding-observation.json",
        "\"normalized_location_signature\": \"sig-items-id-injection\"",
        "\"normalized_location_signature\": \"sig-other\"");
    assertMutationChangesHash("finding-observation.json", "\"verification_key\": \"check-sqli-items-id\"",
        "\"verification_key\": \"check-other\"");
    assertMutationChangesHash("finding-observation.json", "\"check_version\": \"2.3.0\"",
        "\"check_version\": \"2.4.0\"");
    assertMutationChangesHash("finding-observation.json", "\"protocol\": \"http\"",
        "\"protocol\": \"grpc\"");
    assertMutationChangesHash("finding-observation.json", "\"port\": 8443", "\"port\": 9443");
    assertMutationChangesHash("finding-observation.json", "\"normalized_route\": \"/api/v1/items\"",
        "\"normalized_route\": \"/api/v2/items\"");
    assertMutationChangesHash("finding-observation.json", "\"depth\": 2", "\"depth\": 3");
    assertMutationChangesHash("finding-observation.json", "\"mode\": \"safe\"", "\"mode\": \"deep\"");
    assertMutationChangesHash("test-attempt.json", "\"result\": \"not_detected\"",
        "\"result\": \"detected\"");
    assertMutationChangesHash("test-attempt.json",
        "\"execution_status\": \"completed\",\n    \"result\": \"not_detected\",",
        "\"execution_status\": \"partial\",\n    \"result\": \"inconclusive\",");
    // last_data_sequence cannot change alone: the completion rule couples it to
    // source_sequence, so this documents the coupled difference.
    String completion = contract("engagement-completed.json");
    String shiftedCompletion =
        completion
            .replace("\"source_sequence\": 4", "\"source_sequence\": 5")
            .replace("\"last_data_sequence\": 3", "\"last_data_sequence\": 4");
    assertNotEquals(hashOf(completion), hashOf(shiftedCompletion));
  }

  @Test
  void remainingFindingFieldsChangeTheHash() {
    assertMutationChangesHash(
        "finding-observation.json",
        "\"cluster_id\": \"cluster-demo\"",
        "\"cluster_id\": \"cluster-other\"");
    assertMutationChangesHash(
        "finding-observation.json",
        "\"resource_uid\": \"deploy-uid-123\"",
        "\"resource_uid\": \"deploy-uid-124\"");
    assertMutationChangesHash(
        "finding-observation.json",
        "\"follow_redirects\": true",
        "\"follow_redirects\": false");

    String finding = contract("finding-observation.json");
    String withAddress =
        finding.replace(
            "\"is_address_bound\": false",
            "\"target_address\": \"10.0.0.7\",\n      \"is_address_bound\": false");
    assertNotEquals(hashOf(finding), hashOf(withAddress), "target_address");
    assertNotEquals(
        hashOf(withAddress),
        hashOf(withAddress.replace("\"is_address_bound\": false", "\"is_address_bound\": true")),
        "is_address_bound");
  }

  @Test
  void everyAttemptTargetFieldChangesTheHash() {
    assertMutationChangesHash(
        "test-attempt.json",
        "\"cluster_id\": \"cluster-demo\"",
        "\"cluster_id\": \"cluster-other\"");
    assertMutationChangesHash(
        "test-attempt.json",
        "\"resource_uid\": \"deploy-uid-123\"",
        "\"resource_uid\": \"deploy-uid-124\"");
    assertMutationChangesHash(
        "test-attempt.json",
        "\"verification_key\": \"check-sqli-items-id\"",
        "\"verification_key\": \"check-other\"");
    assertMutationChangesHash(
        "test-attempt.json",
        "\"check_version\": \"2.3.0\"",
        "\"check_version\": \"2.4.0\"");
  }

  @Test
  void everyAttemptContextFieldChangesTheHash() {
    assertMutationChangesHash(
        "test-attempt.json", "\"protocol\": \"http\"", "\"protocol\": \"grpc\"");
    assertMutationChangesHash("test-attempt.json", "\"port\": 8443", "\"port\": 9443");
    assertMutationChangesHash(
        "test-attempt.json",
        "\"normalized_route\": \"/api/v1/items\"",
        "\"normalized_route\": \"/api/v2/items\"");
    assertMutationChangesHash("test-attempt.json", "\"depth\": 2", "\"depth\": 3");
    assertMutationChangesHash(
        "test-attempt.json",
        "\"follow_redirects\": true",
        "\"follow_redirects\": false");
    assertMutationChangesHash(
        "test-attempt.json", "\"mode\": \"safe\"", "\"mode\": \"deep\"");
  }

  @Test
  void canonicalBytesAreDefensivelyCopied() {
    CanonicalSourceEvent canonical = codec.decode(contract("asset-observation.json"));
    byte[] exposed = canonical.canonicalJson();
    exposed[0] = 'X';
    assertEquals(contract("asset-observation.canonical.json").strip(),
        new String(canonical.canonicalJson(), UTF_8));
    assertEquals(ASSET_HASH, canonical.canonicalSourceHash());
  }

  private void assertMutationChangesHash(String resource, String target, String replacement) {
    String base = contract(resource);
    String mutated = base.replace(target, replacement);
    assertNotEquals(base, mutated, "mutation target not found: " + target);
    assertNotEquals(hashOf(base), hashOf(mutated), target);
  }

  private String hashOf(String json) {
    return codec.decode(json).canonicalSourceHash();
  }

  private static String weakAssetJson(String resourceUidField) {
    return """
        {"source_event_id":"evt-weak-001","event_type":"asset_observation",
         "source_stream_id":"stream-alpha","source_sequence":6,"schema_version":1,
         "occurred_at":"2026-01-01T10:00:00Z",
         "payload":{"cluster_id":"cluster-demo","resource_kind":"Deployment",%s
          "dns":"api.demo.svc.cluster.local"}}
        """
        .formatted(resourceUidField);
  }
}
