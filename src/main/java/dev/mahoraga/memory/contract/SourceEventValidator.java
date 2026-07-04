package dev.mahoraga.memory.contract;

import dev.mahoraga.memory.contract.SourcePayload.AssetObservation;
import dev.mahoraga.memory.contract.SourcePayload.EngagementCompleted;
import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.FindingObservation;
import dev.mahoraga.memory.contract.SourcePayload.TestAttempt;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Semantic validation of a structurally parsed {@link SourceEvent}. */
public final class SourceEventValidator {

  public static final int SUPPORTED_COMPATIBILITY_POLICY_VERSION = 1;
  public static final String MVP_RESOURCE_KIND = "Deployment";

  private final Validator validator;

  @Inject
  public SourceEventValidator(Validator validator) {
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  public void validate(SourceEvent event) {
    Set<ConstraintViolation<SourceEvent>> violations = validator.validate(event);
    if (!violations.isEmpty()) {
      throw new InvalidSourceEventException(prefix(event) + describe(violations));
    }
    validateEnvelope(event);
    validatePayloadMatchesEventType(event);
    switch (event.payload()) {
      case AssetObservation payload -> validateAssetObservation(event, payload);
      case FindingObservation payload -> validateFindingObservation(event, payload);
      case TestAttempt payload -> validateTestAttempt(event, payload);
      case EngagementCompleted payload -> validateCompletion(event, payload);
    }
  }

  private void validatePayloadMatchesEventType(SourceEvent event) {
    if (!event.eventType().payloadType().isInstance(event.payload())) {
      fail(
          event,
          "event_type " + event.eventType().wireValue() + " does not match payload");
    }
  }

  private void validateEnvelope(SourceEvent event) {
    if (event.schemaVersion() != SourceEventCodec.SUPPORTED_SCHEMA_VERSION) {
      fail(event, "schema_version must be " + SourceEventCodec.SUPPORTED_SCHEMA_VERSION);
    }
    // PostgreSQL stores timestamptz at microsecond precision; a finer canonical
    // timestamp could never be reproduced exactly from storage.
    if (event.occurredAt().getNano() % 1_000 != 0) {
      fail(event, "occurred_at must not be finer than microsecond precision");
    }
  }

  private void validateAssetObservation(SourceEvent event, AssetObservation payload) {
    requireMvpResourceKind(event, payload.resourceKind());
    nonblankIfPresent(event, "resource_uid", payload.resourceUid());
    nonblankIfPresent(event, "pod_uid", payload.podUid());
    nonblankIfPresent(event, "pod_name", payload.podName());
    nonblankIfPresent(event, "ip_address", payload.ipAddress());
    nonblankIfPresent(event, "dns", payload.dns());
    nonblankIfPresent(event, "banner", payload.banner());
    if (payload.labels() != null) {
      validateLabels(event, payload.labels());
    }
    if (!hasObservationSignal(payload)) {
      fail(event, "asset observation requires at least one observation signal");
    }
  }

  private static boolean hasObservationSignal(AssetObservation payload) {
    return payload.podUid() != null
        || payload.podName() != null
        || payload.ipAddress() != null
        || payload.dns() != null
        || (payload.labels() != null && !payload.labels().isEmpty())
        || payload.banner() != null;
  }

  private void validateLabels(SourceEvent event, Map<String, String> labels) {
    for (Map.Entry<String, String> label : labels.entrySet()) {
      if (label.getKey().isBlank()) {
        fail(event, "labels keys must be nonblank");
      }
      if (label.getValue() == null || label.getValue().isBlank()) {
        fail(event, "labels values must be nonblank");
      }
    }
  }

  private void validateFindingObservation(SourceEvent event, FindingObservation payload) {
    requireMvpResourceKind(event, payload.resourceKind());
    requireSupportedPolicyVersion(event, payload.compatibilityPolicyVersion());
    validateRelevantContext(event, payload.relevantContext());
  }

  private void validateTestAttempt(SourceEvent event, TestAttempt payload) {
    requireMvpResourceKind(event, payload.resourceKind());
    requireSupportedPolicyVersion(event, payload.compatibilityPolicyVersion());
    validateRelevantContext(event, payload.relevantContext());
    validateAttemptOutcome(event, payload.executionStatus(), payload.result());
  }

  private void validateAttemptOutcome(SourceEvent event, ExecutionStatus status, TestResult result) {
    if (status == ExecutionStatus.COMPLETED) {
      if (result != TestResult.DETECTED && result != TestResult.NOT_DETECTED) {
        fail(event, "execution_status completed requires result detected or not_detected");
      }
      return;
    }
    if (result == TestResult.DETECTED || result == TestResult.NOT_DETECTED) {
      fail(event, "result " + result.wireValue() + " requires execution_status completed");
    }
  }

  private void validateCompletion(SourceEvent event, EngagementCompleted payload) {
    if (event.sourceSequence() != payload.lastDataSequence() + 1) {
      fail(event, "completion source_sequence must equal last_data_sequence + 1");
    }
  }

  private void validateRelevantContext(SourceEvent event, RelevantContext context) {
    boolean hasTargetAddress =
        context.targetAddress() != null && !context.targetAddress().isBlank();
    if (context.isAddressBound() && !hasTargetAddress) {
      fail(event, "relevant_context.target_address is required when is_address_bound is true");
    }
    nonblankIfPresent(event, "relevant_context.target_address", context.targetAddress());
    if (context.parameters() != null) {
      validateParameters(event, context.parameters());
    }
  }

  private void validateParameters(SourceEvent event, Map<String, Object> parameters) {
    for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
      if (parameter.getKey().isBlank()) {
        fail(event, "relevant_context.parameters keys must be nonblank");
      }
      if (!isJsonScalar(parameter.getValue())) {
        fail(event, "relevant_context.parameters." + parameter.getKey() + " must be a JSON scalar");
      }
    }
  }

  // This whitelist also keeps CanonicalEncoding.canonicalScalar complete:
  // floats only ever arrive as BigDecimal (never Double/Float), so extending
  // it requires extending that normalization or fingerprints become unstable.
  private static boolean isJsonScalar(Object value) {
    return value instanceof String
        || value instanceof Boolean
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger
        || value instanceof BigDecimal;
  }

  private void requireMvpResourceKind(SourceEvent event, String resourceKind) {
    if (!MVP_RESOURCE_KIND.equals(resourceKind)) {
      fail(event, "resource_kind must be " + MVP_RESOURCE_KIND);
    }
  }

  private void requireSupportedPolicyVersion(SourceEvent event, Integer policyVersion) {
    if (policyVersion != SUPPORTED_COMPATIBILITY_POLICY_VERSION) {
      fail(event, "compatibility_policy_version must be " + SUPPORTED_COMPATIBILITY_POLICY_VERSION);
    }
  }

  private void nonblankIfPresent(SourceEvent event, String field, String value) {
    if (value != null && value.isBlank()) {
      fail(event, field + " must be nonblank when present");
    }
  }

  private static String describe(Set<ConstraintViolation<SourceEvent>> violations) {
    return violations.stream()
        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
        .sorted()
        .collect(Collectors.joining("; "));
  }

  private static void fail(SourceEvent event, String message) {
    throw new InvalidSourceEventException(prefix(event) + message);
  }

  private static String prefix(SourceEvent event) {
    String eventId = event.sourceEventId();
    return "source event " + (eventId == null || eventId.isBlank() ? "<unknown>" : eventId) + ": ";
  }
}
