package dev.mahoraga.memory.demo;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.planning.SteeringArmEvidence;
import dev.mahoraga.memory.planning.SteeringArmEvidence.ArmMode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Normalized, machine-verifiable evidence produced by one guarded demo arm. */
@JsonPropertyOrder({
  "evidence_schema_version", "build_fingerprint", "arm_mode", "candidate_input_digest",
  "candidate_ids", "executed_order", "candidate_source_event_ids",
  "actions_before_regression", "planner_boundary_hash", "has_zero_e2_events_at_planning",
  "e1_semantic_digest", "e2_fact_set_digest", "memory_report_digest",
  "stable_identity_proof", "ambiguity_proof", "stateless_report_summary",
  "memory_report_summary", "stateless_report_digest", "duplicate_retry_result",
  "conflicting_duplicate_result", "missing_completion_result", "shuffle_digest_equality",
  "rollback_probe_has_partial_state"
})
public record DemoArmEvidence(
    int evidenceSchemaVersion, String buildFingerprint, ArmMode armMode,
    String candidateInputDigest, List<String> candidateIds, List<String> executedOrder,
    Map<String, List<String>> candidateSourceEventIds,
    int actionsBeforeRegression, String plannerBoundaryHash,
    boolean hasZeroE2EventsAtPlanning,
    String e1SemanticDigest, String e2FactSetDigest, String memoryReportDigest,
    StableIdentityProof stableIdentityProof, AmbiguityProof ambiguityProof,
    StatelessReportSummary statelessReportSummary, MemoryReportSummary memoryReportSummary,
    String statelessReportDigest, IngestResult duplicateRetryResult,
    ConflictProbeResult conflictingDuplicateResult,
    CompletionProbeResult missingCompletionResult,
    boolean shuffleDigestEquality, boolean rollbackProbeHasPartialState) {

  public static final int SCHEMA_VERSION = 1;
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  public DemoArmEvidence {
    if (evidenceSchemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "demo arm evidence schema version must be " + SCHEMA_VERSION);
    }
    requireDigest(buildFingerprint, "buildFingerprint");
    Objects.requireNonNull(armMode, "armMode");
    requireDigest(candidateInputDigest, "candidateInputDigest");
    candidateIds = copyUniqueNonempty(candidateIds, "candidateIds");
    executedOrder = copyUniqueNonempty(executedOrder, "executedOrder");
    if (!candidateIds.containsAll(executedOrder)) {
      throw new IllegalArgumentException("executedOrder contains an unknown candidate");
    }
    candidateSourceEventIds = copyCandidateEvents(candidateSourceEventIds, executedOrder);
    if (actionsBeforeRegression < 1 || actionsBeforeRegression > executedOrder.size()) {
      throw new IllegalArgumentException("actionsBeforeRegression is not an executed position");
    }
    requireDigest(plannerBoundaryHash, "plannerBoundaryHash");
    requireDigest(e1SemanticDigest, "e1SemanticDigest");
    requireDigest(e2FactSetDigest, "e2FactSetDigest");
    requireDigest(memoryReportDigest, "memoryReportDigest");
    requirePresent(
        stableIdentityProof, ambiguityProof, statelessReportSummary, memoryReportSummary,
        duplicateRetryResult, conflictingDuplicateResult, missingCompletionResult);
    requireDigest(statelessReportDigest, "statelessReportDigest");
  }

  public static DemoArmEvidence from(
      String buildFingerprint,
      SteeringArmEvidence steering,
      DemoExecutionProof execution,
      DemoRollbackProof rollback) {
    Objects.requireNonNull(steering, "steering");
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(rollback, "rollback");
    return new DemoArmEvidence(
        SCHEMA_VERSION, buildFingerprint, steering.armMode(),
        steering.candidateInputDigest(), steering.candidateIds(), steering.executedOrder(),
        steering.candidateSourceEventIds(),
        steering.actionsBeforeRegression(), steering.plannerBoundaryHash(),
        steering.hasZeroE2EventsAtPlanning(),
        steering.e1SemanticDigest(), steering.e2FactSetDigest(), steering.memoryReportDigest(),
        execution.stableIdentityProof(), execution.ambiguityProof(),
        execution.statelessReportSummary(), execution.memoryReportSummary(),
        execution.statelessReportDigest(), execution.duplicateRetryResult(),
        execution.conflictingDuplicateResult(),
        rollback.missingCompletionResult(),
        execution.shuffleDigestEquality(), rollback.hasPartialState());
  }

  public SteeringArmEvidence steeringEvidence() {
    return new SteeringArmEvidence(
        armMode,
        candidateInputDigest,
        candidateIds,
        executedOrder,
        candidateSourceEventIds,
        actionsBeforeRegression,
        plannerBoundaryHash,
        hasZeroE2EventsAtPlanning,
        e1SemanticDigest,
        e2FactSetDigest,
        memoryReportDigest);
  }

  private static List<String> copyUniqueNonempty(List<String> values, String field) {
    List<String> copy = List.copyOf(Objects.requireNonNull(values, field));
    if (copy.isEmpty() || copy.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException(field + " must contain nonblank values");
    }
    if (new HashSet<>(copy).size() != copy.size()) {
      throw new IllegalArgumentException(field + " must contain unique values");
    }
    return copy;
  }

  private static Map<String, List<String>> copyCandidateEvents(
      Map<String, List<String>> events, List<String> executedOrder) {
    Objects.requireNonNull(events, "candidateSourceEventIds");
    if (!events.keySet().equals(Set.copyOf(executedOrder))) {
      throw new IllegalArgumentException(
          "candidateSourceEventIds must cover exactly the executed candidates");
    }
    Map<String, List<String>> copy = new LinkedHashMap<>();
    for (String candidateId : executedOrder) {
      copy.put(candidateId, copyUniqueNonempty(events.get(candidateId), "candidate source events"));
    }
    return Map.copyOf(copy);
  }

  static void requireDigest(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
  }

  private static void requirePresent(Object... values) {
    for (Object value : values) {
      Objects.requireNonNull(value, "demo arm evidence field");
    }
  }

  @JsonPropertyOrder({"pod_uid_name_ip_changed", "canonical_deployment_unchanged"})
  public record StableIdentityProof(
      boolean podUidNameIpChanged, boolean canonicalDeploymentUnchanged) {}

  @JsonPropertyOrder({"outcome", "posture_delta"})
  public record AmbiguityProof(String outcome, int postureDelta) {
    public AmbiguityProof {
      if (outcome == null || outcome.isBlank() || postureDelta < 0) {
        throw new IllegalArgumentException("ambiguity proof is invalid");
      }
    }
  }

  @JsonPropertyOrder({"detected", "not_detected", "partial"})
  public record StatelessReportSummary(int detected, int notDetected, int partial) {
    public StatelessReportSummary {
      requireNonnegative(detected, notDetected, partial);
    }
  }

  @JsonPropertyOrder({
    "new_count", "still_open_count", "verified_resolved_count",
    "regressed_count", "not_retested_count", "inconclusive_count"
  })
  public record MemoryReportSummary(
      long newCount, long stillOpenCount, long verifiedResolvedCount,
      long regressedCount, long notRetestedCount, long inconclusiveCount) {
    public MemoryReportSummary {
      requireNonnegative(
          newCount,
          stillOpenCount,
          verifiedResolvedCount,
          regressedCount,
          notRetestedCount,
          inconclusiveCount);
    }
  }

  public enum ConflictProbeResult {
    EVENT_CONTENT_REJECTED
  }

  public enum CompletionProbeResult {
    UNFINALIZED_REPORT_BLOCKED
  }

  private static void requireNonnegative(long... counts) {
    for (long count : counts) {
      if (count < 0) {
        throw new IllegalArgumentException("report counts must be nonnegative");
      }
    }
  }

  static final class EvidenceJson {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper MAPPER = mapper();

    static <T> T read(Path input, Class<T> type) throws IOException {
      Path path = Objects.requireNonNull(input, "input");
      byte[] bytes;
      try (InputStream stream = Files.newInputStream(path)) {
        bytes = stream.readNBytes(MAX_BYTES + 1);
      }
      if (bytes.length > MAX_BYTES) {
        throw new IOException("demo evidence exceeds the 1 MiB input limit");
      }
      return MAPPER.readValue(bytes, type);
    }

    static void write(Path output, Object evidence) throws IOException {
      byte[] bytes = canonicalJson(evidence).getBytes(StandardCharsets.UTF_8);
      Path target = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
      Path temporary = null;
      Throwable failure = null;
      try {
        Path parent = Objects.requireNonNull(target.getParent(), "output parent");
        temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
        temporary = null;
      } catch (IOException | RuntimeException e) {
        failure = e;
        throw e;
      } finally {
        deleteTemporary(temporary, failure);
      }
    }

    static String canonicalJson(Object evidence) {
      try {
        String json = MAPPER.writeValueAsString(evidence);
        requireWithinLimit(json.getBytes(StandardCharsets.UTF_8));
        return json;
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("validated demo evidence failed to serialize", e);
      }
    }

    private static ObjectMapper mapper() {
      ObjectMapper mapper = new ObjectMapper();
      mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
      mapper.getFactory().setStreamReadConstraints(
          StreamReadConstraints.builder().maxNestingDepth(30).build());
      mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      mapper.enable(
          DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
          DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
          DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
          DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
          DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
      mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
      return mapper;
    }

    private static void deleteTemporary(Path temporary, Throwable failure) throws IOException {
      if (temporary == null) {
        return;
      }
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanupFailure) {
        if (failure == null) {
          throw cleanupFailure;
        }
        failure.addSuppressed(cleanupFailure);
      }
    }

    private static void requireWithinLimit(byte[] bytes) {
      if (bytes.length > MAX_BYTES) {
        throw new IllegalArgumentException("demo evidence exceeds the 1 MiB limit");
      }
    }
  }
}
