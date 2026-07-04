package dev.mahoraga.memory.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.contract.SourcePayload.AssetObservation;
import dev.mahoraga.memory.contract.SourcePayload.EngagementCompleted;
import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.FindingObservation;
import dev.mahoraga.memory.contract.SourcePayload.TestAttempt;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import io.dropwizard.validation.BaseValidator;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceEventValidatorTest {

  private static final Instant VALID_TIME = Instant.parse("2026-01-01T10:00:00Z");

  private final SourceEventValidator validator =
      new SourceEventValidator(BaseValidator.newValidator());

  @Test
  void acceptsValidEventsOfEveryType() {
    assertDoesNotThrow(() -> validator.validate(assetEvent(validAsset())));
    assertDoesNotThrow(() -> validator.validate(findingEvent(validFinding())));
    assertDoesNotThrow(
        () -> validator.validate(attemptEvent(attempt(ExecutionStatus.COMPLETED, TestResult.DETECTED))));
    assertDoesNotThrow(() -> validator.validate(completionEvent(4, 3)));
  }

  @Test
  void rejectsInvalidEnvelopeValues() {
    assertInvalid(new SourceEvent(" ", EventType.ASSET_OBSERVATION, "s", 1, 1, VALID_TIME, validAsset()));
    assertInvalid(new SourceEvent("e", EventType.ASSET_OBSERVATION, " ", 1, 1, VALID_TIME, validAsset()));
    assertInvalid(new SourceEvent("e", EventType.ASSET_OBSERVATION, "s", 0, 1, VALID_TIME, validAsset()));
    assertInvalid(new SourceEvent("e", EventType.ASSET_OBSERVATION, "s", 1, 2, VALID_TIME, validAsset()));
    assertInvalid(new SourceEvent("e", EventType.ASSET_OBSERVATION, "s", 1, 1, null, validAsset()));
  }

  @Test
  void rejectsEveryEventTypePayloadMismatch() {
    Map<EventType, SourcePayload> payloads =
        Map.of(
            EventType.ASSET_OBSERVATION,
            validAsset(),
            EventType.FINDING_OBSERVATION,
            validFinding(),
            EventType.TEST_ATTEMPT,
            attempt(ExecutionStatus.COMPLETED, TestResult.DETECTED),
            EventType.ENGAGEMENT_COMPLETED,
            new EngagementCompleted(3L));

    for (EventType eventType : EventType.values()) {
      for (Map.Entry<EventType, SourcePayload> entry : payloads.entrySet()) {
        if (eventType != entry.getKey()) {
          SourceEvent event =
              new SourceEvent("evt-mismatch", eventType, "s", 4, 1, VALID_TIME, entry.getValue());
          assertInvalid(event);
        }
      }
    }
  }

  @Test
  void rejectsSubMicrosecondOccurrenceTime() {
    assertInvalid(
        new SourceEvent(
            "e", EventType.ASSET_OBSERVATION, "s", 1, 1, VALID_TIME.plusNanos(1), validAsset()));
    assertDoesNotThrow(
        () ->
            validator.validate(
                new SourceEvent(
                    "e", EventType.ASSET_OBSERVATION, "s", 1, 1,
                    VALID_TIME.plusNanos(123_456_000).plusNanos(789_000), validAsset())));
  }

  @Test
  void rejectsInvalidAssetObservations() {
    assertInvalid(assetEvent(asset("Pod", "uid-1", "pod-1")));
    assertInvalid(assetEvent(asset("Deployment", "uid-1", null)));
    assertInvalid(assetEvent(asset("Deployment", " ", "pod-1")));
    Map<String, String> blankValue = new HashMap<>();
    blankValue.put("app", " ");
    assertInvalid(
        assetEvent(
            new AssetObservation(
                "c", "Deployment", "uid-1", null, null, null, null, blankValue, null)));
  }

  @Test
  void rejectsInvalidFindingObservations() {
    assertInvalid(findingEvent(finding(null, "2.3.0", validContext(), 1)));
    assertInvalid(findingEvent(finding("uid-1", null, validContext(), 1)));
    assertInvalid(findingEvent(finding("uid-1", "2.3.0", null, 1)));
    assertInvalid(findingEvent(finding("uid-1", "2.3.0", validContext(), 2)));
  }

  @Test
  void rejectsInvalidRelevantContexts() {
    assertInvalid(findingEvent(finding("uid-1", "2.3.0", context(0, null, false), 1)));
    assertInvalid(findingEvent(finding("uid-1", "2.3.0", context(65_536, null, false), 1)));
    assertInvalid(findingEvent(finding("uid-1", "2.3.0", context(8443, null, true), 1)));
    assertInvalid(findingEvent(finding("uid-1", "2.3.0", context(8443, " ", true), 1)));
    assertDoesNotThrow(
        () ->
            validator.validate(
                findingEvent(finding("uid-1", "2.3.0", context(8443, "10.0.0.9:8443", true), 1))));
  }

  @Test
  void rejectsNonScalarAndNullContextParameters() {
    Map<String, Object> nested = new HashMap<>();
    nested.put("nested", Map.of("a", 1));
    assertInvalid(findingEvent(finding("uid-1", "2.3.0", contextWithParameters(nested), 1)));
    Map<String, Object> nullValue = new HashMap<>();
    nullValue.put("mode", null);
    assertInvalid(findingEvent(finding("uid-1", "2.3.0", contextWithParameters(nullValue), 1)));
  }

  @Test
  void acceptsOnlyLegalExecutionStatusResultPairs() {
    record Outcome(ExecutionStatus status, TestResult result) {}
    Outcome[] legal = {
      new Outcome(ExecutionStatus.COMPLETED, TestResult.DETECTED),
      new Outcome(ExecutionStatus.COMPLETED, TestResult.NOT_DETECTED),
      new Outcome(ExecutionStatus.FAILED, null),
      new Outcome(ExecutionStatus.FAILED, TestResult.INCONCLUSIVE),
      new Outcome(ExecutionStatus.BLOCKED, TestResult.INCONCLUSIVE),
      new Outcome(ExecutionStatus.PARTIAL, TestResult.INCONCLUSIVE),
      new Outcome(ExecutionStatus.PARTIAL, null),
      new Outcome(ExecutionStatus.SKIPPED, null),
    };
    Outcome[] illegal = {
      new Outcome(ExecutionStatus.COMPLETED, null),
      new Outcome(ExecutionStatus.COMPLETED, TestResult.INCONCLUSIVE),
      new Outcome(ExecutionStatus.FAILED, TestResult.DETECTED),
      new Outcome(ExecutionStatus.FAILED, TestResult.NOT_DETECTED),
      new Outcome(ExecutionStatus.BLOCKED, TestResult.NOT_DETECTED),
      new Outcome(ExecutionStatus.PARTIAL, TestResult.DETECTED),
      new Outcome(ExecutionStatus.SKIPPED, TestResult.NOT_DETECTED),
    };
    for (Outcome outcome : legal) {
      assertDoesNotThrow(
          () -> validator.validate(attemptEvent(attempt(outcome.status(), outcome.result()))),
          outcome.toString());
    }
    for (Outcome outcome : illegal) {
      assertInvalid(attemptEvent(attempt(outcome.status(), outcome.result())), outcome.toString());
    }
  }

  @Test
  void rejectsInvalidCompletionEvents() {
    assertInvalid(completionEvent(5, 3));
    assertInvalid(completionEvent(1, 0));
  }

  @Test
  void rejectsBlankTrustedContext() {
    assertThrows(IllegalArgumentException.class, () -> new TrustedContext(" ", "eng-1"));
    assertThrows(IllegalArgumentException.class, () -> new TrustedContext(null, "eng-1"));
    assertThrows(IllegalArgumentException.class, () -> new TrustedContext("tenant-1", " "));
    assertThrows(IllegalArgumentException.class, () -> new TrustedContext("tenant-1", null));
    assertDoesNotThrow(() -> new TrustedContext("tenant-1", "eng-1"));
  }

  private void assertInvalid(SourceEvent event) {
    assertInvalid(event, "");
  }

  private void assertInvalid(SourceEvent event, String description) {
    InvalidSourceEventException error =
        assertThrows(
            InvalidSourceEventException.class, () -> validator.validate(event), description);
    assertTrue(error.getMessage().startsWith("source event "), description);
  }

  private static SourceEvent assetEvent(AssetObservation payload) {
    return new SourceEvent("evt-1", EventType.ASSET_OBSERVATION, "s", 1, 1, VALID_TIME, payload);
  }

  private static SourceEvent findingEvent(FindingObservation payload) {
    return new SourceEvent("evt-1", EventType.FINDING_OBSERVATION, "s", 1, 1, VALID_TIME, payload);
  }

  private static SourceEvent attemptEvent(TestAttempt payload) {
    return new SourceEvent("evt-1", EventType.TEST_ATTEMPT, "s", 1, 1, VALID_TIME, payload);
  }

  private static SourceEvent completionEvent(long sourceSequence, long lastDataSequence) {
    return new SourceEvent(
        "evt-1",
        EventType.ENGAGEMENT_COMPLETED,
        "s",
        sourceSequence,
        1,
        VALID_TIME,
        new EngagementCompleted(lastDataSequence));
  }

  private static AssetObservation validAsset() {
    return asset("Deployment", "uid-1", "pod-1");
  }

  private static AssetObservation asset(String resourceKind, String resourceUid, String podUid) {
    return new AssetObservation(
        "cluster-demo", resourceKind, resourceUid, podUid, null, null, null, null, null);
  }

  private static FindingObservation validFinding() {
    return finding("uid-1", "2.3.0", validContext(), 1);
  }

  private static FindingObservation finding(
      String resourceUid, String checkVersion, RelevantContext context, int policyVersion) {
    return new FindingObservation(
        "cluster-demo",
        "Deployment",
        resourceUid,
        "sql_injection",
        "sig-items-id-injection",
        "check-sqli-items-id",
        checkVersion,
        context,
        policyVersion);
  }

  private static TestAttempt attempt(ExecutionStatus status, TestResult result) {
    return new TestAttempt(
        "cluster-demo",
        "Deployment",
        "uid-1",
        "check-sqli-items-id",
        "2.3.0",
        validContext(),
        status,
        result,
        1);
  }

  private static RelevantContext validContext() {
    return context(8443, null, false);
  }

  private static RelevantContext context(int port, String targetAddress, boolean addressBound) {
    return new RelevantContext(
        "http", port, "/api/v1/items", Map.of("depth", 2), targetAddress, addressBound);
  }

  private static RelevantContext contextWithParameters(Map<String, Object> parameters) {
    return new RelevantContext("http", 8443, "/api/v1/items", parameters, null, false);
  }
}
