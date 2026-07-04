package dev.mahoraga.memory.contract;

import static dev.mahoraga.memory.contract.ContractTestSupport.codec;
import static dev.mahoraga.memory.contract.ContractTestSupport.contract;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mahoraga.memory.contract.SourcePayload.AssetObservation;
import dev.mahoraga.memory.contract.SourcePayload.EngagementCompleted;
import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.FindingObservation;
import dev.mahoraga.memory.contract.SourcePayload.TestAttempt;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import org.junit.jupiter.api.Test;

class SourceEventCodecTest {

  private static final String MINIMAL_COMPLETION =
      """
      {"source_event_id":"evt-x","event_type":"engagement_completed","source_stream_id":"s",
       "source_sequence":4,"schema_version":1,"occurred_at":"2026-01-01T10:00:00Z",
       "payload":{"last_data_sequence":3}}
      """;

  private final SourceEventCodec codec = codec();

  @Test
  void parsesAllFourEventTypes() {
    AssetObservation asset =
        assertInstanceOf(
            AssetObservation.class,
            codec.decode(contract("asset-observation.json")).event().payload());
    assertEquals("deploy-uid-123", asset.resourceUid());

    FindingObservation finding =
        assertInstanceOf(
            FindingObservation.class,
            codec.decode(contract("finding-observation.json")).event().payload());
    assertEquals("check-sqli-items-id", finding.verificationKey());
    assertEquals(8443, finding.relevantContext().port());

    TestAttempt attempt =
        assertInstanceOf(
            TestAttempt.class, codec.decode(contract("test-attempt.json")).event().payload());
    assertEquals(ExecutionStatus.COMPLETED, attempt.executionStatus());
    assertEquals(TestResult.NOT_DETECTED, attempt.result());
    assertEquals(finding.relevantContext(), attempt.relevantContext());

    EngagementCompleted completion =
        assertInstanceOf(
            EngagementCompleted.class,
            codec.decode(contract("engagement-completed.json")).event().payload());
    assertEquals(3L, completion.lastDataSequence());
  }

  @Test
  void rejectsUnknownEventType() {
    assertError(MINIMAL_COMPLETION.replace("engagement_completed", "mystery_event"), "event_type");
  }

  @Test
  void rejectsMissingEventType() {
    assertError(MINIMAL_COMPLETION.replace("\"event_type\":\"engagement_completed\",", ""), "event_type");
  }

  @Test
  void rejectsUnsupportedSchemaVersion() {
    assertError(MINIMAL_COMPLETION.replace("\"schema_version\":1", "\"schema_version\":2"), "schema_version");
  }

  @Test
  void rejectsMissingEnvelopeField() {
    assertError(MINIMAL_COMPLETION.replace("\"source_sequence\":4,", ""), "source_sequence");
  }

  @Test
  void rejectsMissingPayload() {
    assertError(
        MINIMAL_COMPLETION.replace(",\n \"payload\":{\"last_data_sequence\":3}", ""), "payload");
  }

  @Test
  void rejectsUnknownEnvelopeField() {
    assertError(
        MINIMAL_COMPLETION.replace("\"schema_version\"", "\"canonical_source_hash\":\"abc\",\"schema_version\""),
        "canonical_source_hash");
  }

  @Test
  void rejectsUnknownPayloadField() {
    assertError(
        MINIMAL_COMPLETION.replace(
            "{\"last_data_sequence\":3}", "{\"last_data_sequence\":3,\"surprise\":1}"),
        "payload");
  }

  @Test
  void rejectsPayloadInconsistentWithEventType() {
    assertError(
        MINIMAL_COMPLETION.replace("engagement_completed", "asset_observation"), "payload");
  }

  @Test
  void rejectsDuplicateEnvelopeKey() {
    assertError(
        MINIMAL_COMPLETION.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"),
        "invalid");
  }

  @Test
  void rejectsDuplicateNestedPayloadKey() {
    assertError(
        MINIMAL_COMPLETION.replace(
            "{\"last_data_sequence\":3}", "{\"last_data_sequence\":3,\"last_data_sequence\":3}"),
        "invalid");
  }

  @Test
  void rejectsInvalidEnumValueWithoutCoercion() {
    assertError(
        contract("test-attempt.json").replace("\"completed\"", "\"sort_of_done\""), "payload");
  }

  @Test
  void rejectsWrongEnvelopeScalarTypes() {
    assertError(
        MINIMAL_COMPLETION.replace("\"source_sequence\":4", "\"source_sequence\":\"4\""),
        "source_sequence");
    assertError(
        MINIMAL_COMPLETION.replace("\"source_sequence\":4", "\"source_sequence\":4.5"),
        "source_sequence");
    assertError(
        MINIMAL_COMPLETION.replace("\"schema_version\":1", "\"schema_version\":\"1\""),
        "schema_version");
    assertError(
        MINIMAL_COMPLETION.replace("\"source_event_id\":\"evt-x\"", "\"source_event_id\":7"),
        "source_event_id");
  }

  @Test
  void rejectsWrongPayloadScalarTypes() {
    assertError(
        MINIMAL_COMPLETION.replace(
            "\"last_data_sequence\":3", "\"last_data_sequence\":\"3\""),
        "last_data_sequence");
    assertError(
        contract("asset-observation.json")
            .replace("\"cluster_id\": \"cluster-demo\"", "\"cluster_id\": 7"),
        "cluster_id");
    assertError(
        contract("finding-observation.json").replace("\"port\": 8443", "\"port\": \"8443\""),
        "port");
    assertError(
        contract("finding-observation.json")
            .replace("\"is_address_bound\": false", "\"is_address_bound\": \"false\""),
        "is_address_bound");
    assertError(
        contract("test-attempt.json")
            .replace("\"execution_status\": \"completed\"", "\"execution_status\": 1"),
        "execution_status");
  }

  @Test
  void rejectsNonFiniteNumbers() {
    String finding = contract("finding-observation.json");
    assertError(finding.replace("\"depth\": 2", "\"depth\": NaN"), "invalid");
    assertError(finding.replace("\"depth\": 2", "\"depth\": Infinity"), "invalid");
  }

  @Test
  void rejectsOversizedInput() {
    String oversized =
        contract("asset-observation.json")
            .replace("nginx/1.25", "x".repeat(SourceEventCodec.MAX_SOURCE_EVENT_BYTES + 1));
    assertError(oversized, "exceeds");
  }

  @Test
  void rejectsExcessiveNesting() {
    int depth = SourceEventCodec.MAX_JSON_NESTING_DEPTH + 5;
    String nested = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth);
    assertError(
        MINIMAL_COMPLETION.replace("{\"last_data_sequence\":3}", nested), "invalid");
  }

  @Test
  void rejectsMalformedJson() {
    assertError("{not json", "invalid");
  }

  @Test
  void errorsIncludeEventIdButNeverThePayload() {
    String input = MINIMAL_COMPLETION.replace("\"schema_version\":1", "\"schema_version\":2");
    InvalidSourceEventException error =
        assertThrows(InvalidSourceEventException.class, () -> codec.decode(input));
    assertTrue(error.getMessage().contains("evt-x"));
    assertTrue(!error.getMessage().contains("last_data_sequence"));
  }

  @Test
  void jacksonErrorsDoNotExposeUnrelatedPayloadValues() {
    String sentinel = "SENSITIVE-BANNER-MUST-NOT-LEAK";
    String input =
        contract("asset-observation.json")
            .replace(
                "\"banner\": \"nginx/1.25\"",
                "\"banner\": \"" + sentinel + "\",\n    \"unexpected\": true");

    InvalidSourceEventException error =
        assertThrows(InvalidSourceEventException.class, () -> codec.decode(input));

    assertFalse(error.getMessage().contains(sentinel), error::getMessage);
  }

  private void assertError(String json, String expectedFragment) {
    assertNotEquals(MINIMAL_COMPLETION, json, "mutation must change the input");
    InvalidSourceEventException error =
        assertThrows(InvalidSourceEventException.class, () -> codec.decode(json));
    assertTrue(
        error.getMessage().toLowerCase().contains(expectedFragment.toLowerCase()),
        "expected '" + expectedFragment + "' in: " + error.getMessage());
  }
}
