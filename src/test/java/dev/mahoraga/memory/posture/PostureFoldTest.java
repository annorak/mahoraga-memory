package dev.mahoraga.memory.posture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import dev.mahoraga.memory.posture.PostureResult.CurrentAssessment;
import dev.mahoraga.memory.posture.PostureResult.EpisodeClassification;
import dev.mahoraga.memory.posture.PostureResult.LastVerifiedExposure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the pure fold: the six exact scenarios, composite same-engagement
 * histories, precedence collisions, incompatible and incomplete evidence,
 * invalid histories, and full arrival-order permutation invariance.
 */
class PostureFoldTest {

  private static final String PRIOR = "eng-1";
  private static final String CURRENT = "eng-2";
  private static final UUID FINDING = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ASSET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String HASH = "a".repeat(64);

  private final PostureFolder folder = new PostureFolder();

  @Test
  void firstOccurrenceInTheCurrentEngagementIsNew() {
    PostureResult result = fold(occurrence(CURRENT, "evt-1", 1));

    assertPosture(result, EpisodeClassification.NEW, CurrentAssessment.DETECTED,
        LastVerifiedExposure.OPEN);
  }

  @Test
  void priorOpenWithACurrentOccurrenceIsStillOpen() {
    PostureResult result = fold(occurrence(PRIOR, "evt-1", 1), occurrence(CURRENT, "evt-2", 2));

    assertPosture(result, EpisodeClassification.STILL_OPEN, CurrentAssessment.DETECTED,
        LastVerifiedExposure.OPEN);
  }

  @Test
  void priorOpenWithACompatibleCurrentNegativeIsVerifiedResolved() {
    PostureResult result =
        fold(occurrence(PRIOR, "evt-1", 1), negative(CURRENT, "evt-2", 2));

    assertPosture(result, EpisodeClassification.VERIFIED_RESOLVED,
        CurrentAssessment.NOT_DETECTED, LastVerifiedExposure.VERIFIED_RESOLVED);
  }

  @Test
  void priorResolvedWithACurrentOccurrenceIsRegressed() {
    PostureResult result =
        fold(occurrence(PRIOR, "evt-1", 1), negative(PRIOR, "evt-2", 2),
            occurrence(CURRENT, "evt-3", 3));

    assertPosture(result, EpisodeClassification.REGRESSED, CurrentAssessment.DETECTED,
        LastVerifiedExposure.OPEN);
  }

  @Test
  void priorOpenWithNoCurrentEvidenceIsNotRetestedAndStaysOpen() {
    PostureResult result = fold(occurrence(PRIOR, "evt-1", 1));

    assertPosture(result, EpisodeClassification.NOT_RETESTED, CurrentAssessment.NOT_RETESTED,
        LastVerifiedExposure.OPEN);
  }

  @Test
  void priorOpenWithOnlyIncompleteCurrentEvidenceIsInconclusive() {
    PostureResult partial =
        fold(occurrence(PRIOR, "evt-1", 1),
            attempt(CURRENT, "evt-2", 2, ExecutionStatus.PARTIAL, TestResult.INCONCLUSIVE));
    PostureResult failed =
        fold(occurrence(PRIOR, "evt-1", 1),
            attempt(CURRENT, "evt-2", 2, ExecutionStatus.FAILED, null));

    assertPosture(partial, EpisodeClassification.INCONCLUSIVE, CurrentAssessment.INCONCLUSIVE,
        LastVerifiedExposure.OPEN);
    assertEquals(partial, failed);
  }

  @Test
  void currentDetectionThenCompatibleNegativeResolvesWithinTheEngagement() {
    PostureResult result =
        fold(occurrence(PRIOR, "evt-1", 1), occurrence(CURRENT, "evt-2", 2),
            negative(CURRENT, "evt-3", 3));

    assertPosture(result, EpisodeClassification.VERIFIED_RESOLVED,
        CurrentAssessment.NOT_DETECTED, LastVerifiedExposure.VERIFIED_RESOLVED);
  }

  @Test
  void negativeThenLaterOccurrenceReopensAndRegressionWinsPrecedence() {
    PostureResult result =
        fold(occurrence(PRIOR, "evt-1", 1), negative(CURRENT, "evt-2", 2),
            occurrence(CURRENT, "evt-3", 3));

    assertPosture(result, EpisodeClassification.REGRESSED, CurrentAssessment.DETECTED,
        LastVerifiedExposure.OPEN);
  }

  @Test
  void newWinsPrecedenceOverResolutionInTheSameEngagement() {
    PostureResult result =
        fold(occurrence(CURRENT, "evt-1", 1), negative(CURRENT, "evt-2", 2));

    assertPosture(result, EpisodeClassification.NEW, CurrentAssessment.NOT_DETECTED,
        LastVerifiedExposure.VERIFIED_RESOLVED);
  }

  @Test
  void historicalClosureWithNoCurrentFactCarriesVerifiedResolution() {
    PostureResult result = fold(occurrence(PRIOR, "evt-1", 1), negative(PRIOR, "evt-2", 2));

    assertPosture(result, EpisodeClassification.NOT_RETESTED, CurrentAssessment.NOT_RETESTED,
        LastVerifiedExposure.VERIFIED_RESOLVED);
  }

  @Test
  void historicalRegressionWithNoCurrentFactCarriesOpenExposure() {
    PostureResult result =
        fold(occurrence(PRIOR, "evt-1", 1), negative(PRIOR, "evt-2", 2),
            occurrence(PRIOR, "evt-3", 3));

    assertPosture(result, EpisodeClassification.NOT_RETESTED, CurrentAssessment.NOT_RETESTED,
        LastVerifiedExposure.OPEN);
  }

  @Test
  void incompatibleCompletedNegativeHasNoEffectOfAnyKind() {
    PostureResult result =
        fold(occurrence(PRIOR, "evt-1", 1), incompatibleNegative(CURRENT, "evt-2", 2));

    assertPosture(result, EpisodeClassification.NOT_RETESTED, CurrentAssessment.NOT_RETESTED,
        LastVerifiedExposure.OPEN);
  }

  @Test
  void detectedAttemptPairedWithItsCurrentOccurrenceIsValidCoverage() {
    PostureResult result =
        fold(occurrence(CURRENT, "evt-1", 1),
            attempt(CURRENT, "evt-2", 2, ExecutionStatus.COMPLETED, TestResult.DETECTED));

    assertPosture(result, EpisodeClassification.NEW, CurrentAssessment.DETECTED,
        LastVerifiedExposure.OPEN);
  }

  @Test
  void matchedDetectedAttemptWithoutItsOccurrenceFailsExplicitly() {
    InvalidPostureHistoryException error =
        assertThrows(
            InvalidPostureHistoryException.class,
            () ->
                fold(occurrence(PRIOR, "evt-1", 1),
                    attempt(CURRENT, "evt-2", 2, ExecutionStatus.COMPLETED,
                        TestResult.DETECTED)));

    assertTrue(error.getMessage().contains("evt-2"));
    assertThrows(
        InvalidPostureHistoryException.class,
        () -> fold(attempt(CURRENT, "evt-1", 1, ExecutionStatus.COMPLETED, TestResult.DETECTED)),
        "an occurrence-less history has nothing to classify");
  }

  @Test
  void mixedFindingIdentitiesAreRejected() {
    SelectedFact.FindingOccurrence other =
        new SelectedFact.FindingOccurrence(
            "t-fold", PRIOR, "evt-2", "s-" + PRIOR, 2, at(2), UUID.randomUUID(), ASSET,
            "xss", "route:/login", 1, "check-xss-1", "1.0", HASH, 1);

    assertThrows(
        InvalidPostureHistoryException.class,
        () -> fold(occurrence(PRIOR, "evt-1", 1), other));
  }

  @Test
  void blankEngagementAndNullFactsAreRejectedBeforeFolding() {
    assertThrows(IllegalArgumentException.class,
        () -> folder.fold(" ", List.of(occurrence(PRIOR, "evt-1", 1))));
    assertThrows(IllegalArgumentException.class,
        () -> folder.fold(CURRENT, java.util.Arrays.asList(occurrence(PRIOR, "evt-1", 1), null)));
  }

  @Test
  void everyArrivalPermutationOfTheSameFactsFoldsIdentically() {
    List<SelectedFact> facts =
        List.of(occurrence(PRIOR, "evt-1", 1), negative(CURRENT, "evt-2", 2),
            occurrence(CURRENT, "evt-3", 3),
            attempt(CURRENT, "evt-4", 4, ExecutionStatus.PARTIAL, null));
    PostureResult expected = folder.fold(CURRENT, facts);
    assertPosture(expected, EpisodeClassification.REGRESSED, CurrentAssessment.DETECTED,
        LastVerifiedExposure.OPEN);

    List<List<SelectedFact>> permutations = permutations(facts);
    assertEquals(24, permutations.size());
    for (List<SelectedFact> permutation : permutations) {
      assertEquals(expected, folder.fold(CURRENT, permutation));
    }
  }

  private PostureResult fold(SelectedFact... facts) {
    return folder.fold(CURRENT, List.of(facts));
  }

  private static void assertPosture(
      PostureResult result,
      EpisodeClassification episode,
      CurrentAssessment assessment,
      LastVerifiedExposure exposure) {
    assertEquals(new PostureResult(exposure, assessment, episode), result);
  }

  /** Positions double as minutes so effective time and sequence stay aligned. */
  private static Instant at(long position) {
    return Instant.parse("2026-01-01T10:00:00Z").plusSeconds(position * 60);
  }

  private static SelectedFact.FindingOccurrence occurrence(
      String engagementId, String eventId, long position) {
    return new SelectedFact.FindingOccurrence(
        "t-fold", engagementId, eventId, "s-" + engagementId, position, at(position), FINDING,
        ASSET, "xss", "route:/login", 1, "check-xss-1", "1.0", HASH, 1);
  }

  private static SelectedFact.TestAttempt negative(
      String engagementId, String eventId, long position) {
    return attempt(engagementId, eventId, position, ExecutionStatus.COMPLETED,
        TestResult.NOT_DETECTED);
  }

  private static SelectedFact.TestAttempt attempt(
      String engagementId, String eventId, long position, ExecutionStatus status,
      TestResult result) {
    return new SelectedFact.TestAttempt(
        "t-fold", engagementId, eventId, "s-" + engagementId, position, at(position), ASSET,
        "check-xss-1", "1.0", HASH, 1, status, result);
  }

  private static SelectedFact.TestAttempt incompatibleNegative(
      String engagementId, String eventId, long position) {
    return new SelectedFact.TestAttempt(
        "t-fold", engagementId, eventId, "s-" + engagementId, position, at(position), ASSET,
        "check-xss-1", "2.0", HASH, 1, ExecutionStatus.COMPLETED, TestResult.NOT_DETECTED);
  }

  private static List<List<SelectedFact>> permutations(List<SelectedFact> facts) {
    if (facts.size() <= 1) {
      return List.of(facts);
    }
    List<List<SelectedFact>> all = new ArrayList<>();
    for (int i = 0; i < facts.size(); i++) {
      List<SelectedFact> rest = new ArrayList<>(facts);
      SelectedFact head = rest.remove(i);
      for (List<SelectedFact> tail : permutations(rest)) {
        List<SelectedFact> permutation = new ArrayList<>();
        permutation.add(head);
        permutation.addAll(tail);
        all.add(permutation);
      }
    }
    return all;
  }
}
