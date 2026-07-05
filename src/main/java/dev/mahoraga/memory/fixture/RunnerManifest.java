package dev.mahoraga.memory.fixture;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import java.util.List;

/**
 * The runner-only fixture control record. It is the one place allowed to hold
 * {@code F-*} scenario labels, frozen action outcomes, and the source-event IDs
 * that back each candidate action. Ingestion, identity, coverage, posture, and
 * planner code never accept this type; the planner receives only the narrowed
 * {@link PlannerCandidateSet} projected from it. Lists are copied defensively so
 * the loaded manifest is immutable.
 */
public record RunnerManifest(
    @JsonProperty("action_budget") int actionBudget,
    @JsonProperty("candidates") List<RunnerCandidate> candidates,
    @JsonProperty("background") List<BackgroundEvent> background) {

  public RunnerManifest {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    background = background == null ? List.of() : List.copyOf(background);
  }

  /** One opaque planner candidate wired to its deterministic runner action and frozen result. */
  public record RunnerCandidate(
      @JsonProperty("candidate_id") String candidateId,
      @JsonProperty("scenario_label") String scenarioLabel,
      @JsonProperty("target") DeploymentTarget target,
      @JsonProperty("verification_key") String verificationKey,
      @JsonProperty("action_event_ids") List<String> actionEventIds,
      @JsonProperty("frozen_outcome") FrozenOutcome frozenOutcome) {

    public RunnerCandidate {
      actionEventIds = actionEventIds == null ? List.of() : List.copyOf(actionEventIds);
    }
  }

  /** The outcome the runner will make a candidate's check return when it executes. */
  public record FrozenOutcome(
      @JsonProperty("execution_status") ExecutionStatus executionStatus,
      @JsonProperty("result") TestResult result) {}

  /**
   * A source event the runner ingests for report coverage but never offers to
   * the planner, paired with the {@code F-*} scenario it is expected to prove.
   */
  public record BackgroundEvent(
      @JsonProperty("event_id") String eventId,
      @JsonProperty("scenario_label") String scenarioLabel) {}
}
