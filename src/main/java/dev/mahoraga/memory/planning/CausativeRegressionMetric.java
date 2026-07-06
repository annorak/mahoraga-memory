package dev.mahoraga.memory.planning;

import dev.mahoraga.memory.posture.PostureFolder;
import dev.mahoraga.memory.posture.PostureResult.EpisodeClassification;
import dev.mahoraga.memory.posture.SelectedFact;
import dev.mahoraga.memory.posture.SelectedFact.FindingOccurrence;
import dev.mahoraga.memory.posture.SelectedFact.TestAttempt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Derives {@code actions_before_regression_detection} purely from
 * boundary-selected persisted facts and the recorded execution: the one-based
 * executed position of the single candidate whose recorded occurrence makes a
 * previously verified-resolved finding fold to {@code REGRESSED}. No candidate
 * literal, plan position alone, recorded time, or label is consulted, so
 * reordering references without changing executed facts cannot move the
 * metric.
 */
final class CausativeRegressionMetric {

  private static final PostureFolder FOLDER = new PostureFolder();

  private CausativeRegressionMetric() {}

  static int actionsBeforeRegression(
      List<SelectedFact> facts,
      String currentEngagementId,
      List<String> executedOrder,
      Map<String, List<String>> executedEventIds) {
    Set<String> regressionEventIds = regressionOccurrenceEventIds(facts, currentEngagementId);
    if (regressionEventIds.isEmpty()) {
      throw new IllegalStateException("no regression is present in the persisted history");
    }
    List<String> causative =
        executedOrder.stream()
            .filter(
                candidateId ->
                    executedEventIds.get(candidateId).stream()
                        .anyMatch(regressionEventIds::contains))
            .toList();
    if (causative.size() != 1) {
      throw new IllegalStateException(
          "exactly one executed candidate must cause the regression, found " + causative);
    }
    return executedOrder.indexOf(causative.get(0)) + 1;
  }

  /** Source-event ids of current-engagement occurrences of REGRESSED findings. */
  private static Set<String> regressionOccurrenceEventIds(
      List<SelectedFact> facts, String currentEngagementId) {
    Map<UUID, List<SelectedFact>> occurrencesByFinding = new LinkedHashMap<>();
    List<SelectedFact> attempts = new ArrayList<>();
    for (SelectedFact fact : facts) {
      switch (fact) {
        case FindingOccurrence occurrence ->
            occurrencesByFinding
                .computeIfAbsent(occurrence.findingId(), findingId -> new ArrayList<>())
                .add(occurrence);
        case TestAttempt attempt -> attempts.add(attempt);
      }
    }
    Set<String> regressionEventIds = new HashSet<>();
    for (List<SelectedFact> occurrences : occurrencesByFinding.values()) {
      List<SelectedFact> evidence = new ArrayList<>(occurrences);
      evidence.addAll(attempts);
      if (FOLDER.fold(currentEngagementId, evidence).episodeClassification()
          == EpisodeClassification.REGRESSED) {
        occurrences.stream()
            .filter(fact -> fact.engagementId().equals(currentEngagementId))
            .forEach(fact -> regressionEventIds.add(fact.sourceEventId()));
      }
    }
    return regressionEventIds;
  }
}
