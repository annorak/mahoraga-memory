package dev.mahoraga.memory.planning;

import dev.mahoraga.memory.contract.CanonicalEncoding;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.fixture.CandidateActionSet;
import dev.mahoraga.memory.fixture.FixtureEventSet;
import dev.mahoraga.memory.fixture.PlannerCandidateSet;
import dev.mahoraga.memory.fixture.PlannerCandidateSet.PlannerCandidate;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/**
 * The digest both arms must share over their controlled inputs: tenant,
 * engagement, budget, candidates with targets and keys, and every executable
 * event id with its canonical content hash. Event lists are sorted by id, so
 * reference ordering does not change the digest. Any change to an event's
 * content, including a frozen outcome, changes the digest.
 */
final class ControlledInputDigest {

  private ControlledInputDigest() {}

  static String of(
      PlannerCandidateSet candidates,
      CandidateActionSet actions,
      FixtureEventSet backgroundEvents,
      FixtureEventSet completionEvents) {
    StringBuilder canonical = new StringBuilder();
    canonical.append("tenant=").append(candidates.tenantId()).append('\n');
    canonical.append("engagement=").append(actions.trustedContext().engagementId()).append('\n');
    canonical.append("budget=").append(candidates.actionBudget()).append('\n');
    candidates.candidates().stream()
        .sorted(Comparator.comparing(PlannerCandidate::candidateId))
        .forEach(candidate -> appendCandidate(canonical, candidate, actions));
    appendEvents(canonical, "supporting", actions.supportingEvents());
    appendEvents(canonical, "background", backgroundEvents.events());
    appendEvents(canonical, "completion", completionEvents.events());
    return CanonicalEncoding.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void appendCandidate(
      StringBuilder canonical, PlannerCandidate candidate, CandidateActionSet actions) {
    canonical
        .append("candidate=")
        .append(candidate.candidateId())
        .append('|')
        .append(candidate.target().clusterId())
        .append('|')
        .append(candidate.target().resourceKind())
        .append('|')
        .append(candidate.target().resourceUid())
        .append('|')
        .append(candidate.verificationKey())
        .append('\n');
    appendEvents(
        canonical, "action:" + candidate.candidateId(), actions.actionsFor(candidate.candidateId()));
  }

  private static void appendEvents(
      StringBuilder canonical, String role, List<CanonicalSourceEvent> events) {
    events.stream()
        .sorted(Comparator.comparing(event -> event.event().sourceEventId()))
        .forEach(
            event ->
                canonical
                    .append(role)
                    .append('=')
                    .append(event.event().sourceEventId())
                    .append('|')
                    .append(event.canonicalSourceHash())
                    .append('\n'));
  }
}
