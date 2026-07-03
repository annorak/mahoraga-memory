package dev.mahoraga.memory.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The four typed schema-version-1 payload contracts, discriminated by {@link EventType}. */
public sealed interface SourcePayload {

  /** One observation of a Kubernetes Deployment and its current, changeable runtime signals. */
  record AssetObservation(
      @JsonProperty("cluster_id") @NotBlank String clusterId,
      @JsonProperty("resource_kind") @NotBlank String resourceKind,
      @JsonProperty("resource_uid") String resourceUid,
      @JsonProperty("pod_uid") String podUid,
      @JsonProperty("pod_name") String podName,
      @JsonProperty("ip_address") String ipAddress,
      @JsonProperty("dns") String dns,
      @JsonProperty("labels") Map<String, String> labels,
      @JsonProperty("banner") String banner)
      implements SourcePayload {

    public AssetObservation {
      if (labels != null) {
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
      }
    }
  }

  /** A detection carrying the authoritative asset key and its verification baseline. */
  record FindingObservation(
      @JsonProperty("cluster_id") @NotBlank String clusterId,
      @JsonProperty("resource_kind") @NotBlank String resourceKind,
      @JsonProperty("resource_uid") @NotBlank String resourceUid,
      @JsonProperty("vuln_class") @NotBlank String vulnClass,
      @JsonProperty("normalized_location_signature") @NotBlank String normalizedLocationSignature,
      @JsonProperty("verification_key") @NotBlank String verificationKey,
      @JsonProperty("check_version") @NotBlank String checkVersion,
      @JsonProperty("relevant_context") @NotNull @Valid RelevantContext relevantContext,
      @JsonProperty("compatibility_policy_version") @NotNull Integer compatibilityPolicyVersion)
      implements SourcePayload {}

  /** A coverage fact: this exact check ran, or tried to run, with this status and result. */
  record TestAttempt(
      @JsonProperty("cluster_id") @NotBlank String clusterId,
      @JsonProperty("resource_kind") @NotBlank String resourceKind,
      @JsonProperty("resource_uid") @NotBlank String resourceUid,
      @JsonProperty("verification_key") @NotBlank String verificationKey,
      @JsonProperty("check_version") @NotBlank String checkVersion,
      @JsonProperty("relevant_context") @NotNull @Valid RelevantContext relevantContext,
      @JsonProperty("execution_status") @NotNull ExecutionStatus executionStatus,
      @JsonProperty("result") TestResult result,
      @JsonProperty("compatibility_policy_version") @NotNull Integer compatibilityPolicyVersion)
      implements SourcePayload {}

  /** The producer's claim that data positions 1..last_data_sequence complete the engagement. */
  record EngagementCompleted(
      @JsonProperty("last_data_sequence") @NotNull @Positive Long lastDataSequence)
      implements SourcePayload {}

  /** How far a test attempt's execution got. */
  enum ExecutionStatus {
    COMPLETED("completed"),
    FAILED("failed"),
    BLOCKED("blocked"),
    PARTIAL("partial"),
    SKIPPED("skipped");

    private final String wireValue;

    ExecutionStatus(String wireValue) {
      this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
      return wireValue;
    }

    @JsonCreator
    static ExecutionStatus fromWire(String value) {
      for (ExecutionStatus status : values()) {
        if (status.wireValue.equals(value)) {
          return status;
        }
      }
      throw new IllegalArgumentException("unsupported execution_status: " + value);
    }
  }

  /** What a test attempt established, when it established anything. */
  enum TestResult {
    DETECTED("detected"),
    NOT_DETECTED("not_detected"),
    INCONCLUSIVE("inconclusive");

    private final String wireValue;

    TestResult(String wireValue) {
      this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
      return wireValue;
    }

    @JsonCreator
    static TestResult fromWire(String value) {
      for (TestResult result : values()) {
        if (result.wireValue.equals(value)) {
          return result;
        }
      }
      throw new IllegalArgumentException("unsupported result: " + value);
    }
  }
}
