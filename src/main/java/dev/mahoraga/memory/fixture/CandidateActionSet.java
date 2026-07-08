package dev.mahoraga.memory.fixture;

import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The runner-side execution lookup projected from the runner manifest: for
 * each opaque candidate, the ordered source events its action ingests, plus
 * the planner-dataset events no candidate references (churn observations the
 * engagement needs for completeness). Like {@link PlannerCandidateSet}, it
 * carries no scenario label or separate outcome map. Outcomes remain ordinary
 * source-event content, and those event payloads never enter planner inputs.
 */
public record CandidateActionSet(
    TrustedContext trustedContext,
    Map<String, List<CanonicalSourceEvent>> eventsByCandidateId,
    List<CanonicalSourceEvent> supportingEvents) {

  public CandidateActionSet {
    Objects.requireNonNull(trustedContext, "trustedContext");
    Map<String, List<CanonicalSourceEvent>> copied = new LinkedHashMap<>();
    for (Map.Entry<String, List<CanonicalSourceEvent>> entry :
        Objects.requireNonNull(eventsByCandidateId, "eventsByCandidateId").entrySet()) {
      if (entry.getValue().isEmpty()) {
        throw new InvalidFixtureException(
            "candidate %s declares no action events".formatted(entry.getKey()));
      }
      copied.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    eventsByCandidateId = Map.copyOf(copied);
    supportingEvents = List.copyOf(Objects.requireNonNull(supportingEvents, "supportingEvents"));
  }

  /**
   * Projects the manifest's candidate-to-action references onto the decoded
   * planner-dataset events. Only candidate ids and action event ids are read,
   * so labels and outcome fields cannot cross this boundary.
   */
  public static CandidateActionSet from(RunnerManifest manifest, FixtureEventSet plannerEvents) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(plannerEvents, "plannerEvents");
    Map<String, CanonicalSourceEvent> byEventId = new HashMap<>();
    plannerEvents.events().forEach(event -> byEventId.put(event.event().sourceEventId(), event));
    Map<String, List<CanonicalSourceEvent>> byCandidateId = new LinkedHashMap<>();
    for (RunnerManifest.RunnerCandidate candidate : manifest.candidates()) {
      List<CanonicalSourceEvent> events = new ArrayList<>();
      for (String eventId : candidate.actionEventIds()) {
        CanonicalSourceEvent event = byEventId.get(eventId);
        if (event == null) {
          throw new InvalidFixtureException(
              "candidate %s references unknown action event %s"
                  .formatted(candidate.candidateId(), eventId));
        }
        events.add(event);
      }
      if (byCandidateId.put(candidate.candidateId(), events) != null) {
        throw new InvalidFixtureException(
            "candidate %s appears more than once".formatted(candidate.candidateId()));
      }
    }
    return new CandidateActionSet(
        plannerEvents.trustedContext(), byCandidateId, unreferenced(plannerEvents, byCandidateId));
  }

  /** The ordered action events one planned candidate executes. */
  public List<CanonicalSourceEvent> actionsFor(String candidateId) {
    List<CanonicalSourceEvent> events = eventsByCandidateId.get(candidateId);
    if (events == null) {
      throw new IllegalArgumentException("no action events exist for candidate " + candidateId);
    }
    return events;
  }

  private static List<CanonicalSourceEvent> unreferenced(
      FixtureEventSet plannerEvents, Map<String, List<CanonicalSourceEvent>> byCandidateId) {
    List<String> referencedIds =
        byCandidateId.values().stream()
            .flatMap(List::stream)
            .map(event -> event.event().sourceEventId())
            .toList();
    return plannerEvents.events().stream()
        .filter(event -> !referencedIds.contains(event.event().sourceEventId()))
        .toList();
  }
}
