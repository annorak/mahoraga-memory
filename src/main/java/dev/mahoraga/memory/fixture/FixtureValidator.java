package dev.mahoraga.memory.fixture;

import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.fixture.RunnerManifest.BackgroundEvent;
import dev.mahoraga.memory.fixture.RunnerManifest.RunnerCandidate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural and cross-reference validation for a fixture bundle. Every check
 * rejects the whole bundle before ingestion and reports a stable file, event,
 * candidate, or field identifier without echoing any payload.
 */
final class FixtureValidator {

  /** For schema version 1 the planner sees exactly these three opaque candidates. */
  static final Set<String> CANONICAL_CANDIDATE_IDS = Set.of("T-A", "T-B", "T-C");

  /**
   * Runner-only scenario labels and candidate IDs. A match anywhere in a source
   * payload means the leakage boundary was crossed and the bundle is rejected.
   */
  private static final Pattern RUNNER_TOKEN =
      Pattern.compile("F-(STILL|FIXED|REGRESS|UNTESTED|INCONCLUSIVE|NEW)|T-[ABC]");

  void validateEventSet(FixtureEventSet eventSet) {
    Set<String> eventIds = new HashSet<>();
    Set<String> positions = new HashSet<>();
    for (CanonicalSourceEvent canonical : eventSet.events()) {
      var event = canonical.event();
      if (!eventIds.add(event.sourceEventId())) {
        throw new InvalidFixtureException(
            "event set: duplicate source_event_id " + event.sourceEventId());
      }
      String position = event.sourceStreamId() + "#" + event.sourceSequence();
      if (!positions.add(position)) {
        throw new InvalidFixtureException(
            "event set: duplicate stream position " + position);
      }
      rejectRunnerTokenLeak(canonical);
    }
  }

  private void rejectRunnerTokenLeak(CanonicalSourceEvent canonical) {
    var matcher = RUNNER_TOKEN.matcher(canonical.canonicalPayloadJson());
    if (matcher.find()) {
      throw new InvalidFixtureException(
          "source event "
              + canonical.event().sourceEventId()
              + ": payload contains forbidden runner token "
              + matcher.group());
    }
  }

  void validateManifest(RunnerManifest manifest, FixtureEventSet eventSet) {
    if (manifest.actionBudget() <= 0) {
      throw new InvalidFixtureException("manifest: action_budget must be positive");
    }
    validateCandidateIds(manifest.candidates());
    Set<String> knownEventIds = new HashSet<>(eventSet.eventIds());
    Set<String> actionEventIds = new LinkedHashSet<>();
    for (RunnerCandidate candidate : manifest.candidates()) {
      validateCandidate(candidate, knownEventIds, actionEventIds);
    }
    validateBackground(manifest.background(), knownEventIds, actionEventIds);
  }

  private void validateCandidateIds(List<RunnerCandidate> candidates) {
    Set<String> distinct = new LinkedHashSet<>();
    for (RunnerCandidate candidate : candidates) {
      if (!distinct.add(candidate.candidateId())) {
        throw new InvalidFixtureException(
            "manifest: duplicate candidate_id " + candidate.candidateId());
      }
    }
    if (!distinct.equals(CANONICAL_CANDIDATE_IDS)) {
      throw new InvalidFixtureException(
          "manifest: candidate_ids must be exactly "
              + CANONICAL_CANDIDATE_IDS
              + " but were "
              + distinct);
    }
  }

  private void validateCandidate(
      RunnerCandidate candidate, Set<String> knownEventIds, Set<String> actionEventIds) {
    if (candidate.frozenOutcome() == null || candidate.frozenOutcome().executionStatus() == null) {
      throw new InvalidFixtureException(
          "candidate " + candidate.candidateId() + ": frozen_outcome execution_status is required");
    }
    if (candidate.actionEventIds().isEmpty()) {
      throw new InvalidFixtureException(
          "candidate " + candidate.candidateId() + ": action_event_ids must be nonempty");
    }
    for (String eventId : candidate.actionEventIds()) {
      requireKnownEvent(candidate.candidateId(), eventId, knownEventIds);
      if (!actionEventIds.add(eventId)) {
        throw new InvalidFixtureException(
            "candidate " + candidate.candidateId() + ": action_event_id " + eventId + " is reused");
      }
    }
  }

  private void validateBackground(
      List<BackgroundEvent> background, Set<String> knownEventIds, Set<String> actionEventIds) {
    Set<String> backgroundEventIds = new HashSet<>();
    for (BackgroundEvent event : background) {
      requireKnownEvent("background", event.eventId(), knownEventIds);
      if (!backgroundEventIds.add(event.eventId())) {
        throw new InvalidFixtureException(
            "manifest: duplicate background event_id " + event.eventId());
      }
      if (actionEventIds.contains(event.eventId())) {
        throw new InvalidFixtureException(
            "manifest: event_id " + event.eventId() + " is both a candidate action and background");
      }
    }
  }

  private void requireKnownEvent(String owner, String eventId, Set<String> knownEventIds) {
    if (eventId == null || !knownEventIds.contains(eventId)) {
      throw new InvalidFixtureException(
          owner + ": references unknown source_event_id " + eventId);
    }
  }
}
