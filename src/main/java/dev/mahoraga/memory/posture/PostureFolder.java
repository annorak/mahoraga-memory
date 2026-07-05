package dev.mahoraga.memory.posture;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import dev.mahoraga.memory.coverage.CoverageCompatibilityPolicyV1;
import dev.mahoraga.memory.coverage.FindingVerificationBaseline;
import dev.mahoraga.memory.coverage.RecordedTestAttempt;
import dev.mahoraga.memory.posture.PostureResult.CurrentAssessment;
import dev.mahoraga.memory.posture.PostureResult.EpisodeClassification;
import dev.mahoraga.memory.posture.PostureResult.LastVerifiedExposure;
import dev.mahoraga.memory.posture.SelectedFact.FindingOccurrence;
import dev.mahoraga.memory.posture.SelectedFact.TestAttempt;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The pure longitudinal fold for one finding's boundary-selected facts. It
 * orders evidence by validated effective time with deterministic source
 * tie-breakers, applies exposure and assessment transitions, and returns
 * exactly one of six episode classifications by fixed precedence. It reads no
 * clock, database, environment, or randomness, so equal inputs in any caller
 * order always produce equal output. Attempts are matched with the one
 * coverage policy; incompatible attempts have no effect of any kind.
 */
public final class PostureFolder {

  /**
   * Total domain order: validated effective time, then stream, position, and
   * event id as deterministic tie-breakers. Recorded time is never an input.
   */
  public static final Comparator<SelectedFact> DOMAIN_ORDER =
      Comparator.comparing(SelectedFact::occurredAt)
          .thenComparing(SelectedFact::sourceStreamId)
          .thenComparingLong(SelectedFact::sourceSequence)
          .thenComparing(SelectedFact::sourceEventId);

  public PostureResult fold(String currentEngagementId, List<SelectedFact> facts) {
    requireValidInputs(currentEngagementId, facts);
    FindingVerificationBaseline baseline = requireSingleFindingBaseline(facts);
    List<SelectedFact> evidence =
        facts.stream()
            .filter(fact -> !(fact instanceof TestAttempt attempt) || isCompatible(attempt, baseline))
            .sorted(DOMAIN_ORDER)
            .toList();
    requireOccurrenceForDetectedAttempts(evidence);
    FoldState state = applyInOrder(evidence, currentEngagementId);
    return new PostureResult(state.exposure, assessment(state), classify(state));
  }

  private static FoldState applyInOrder(List<SelectedFact> evidence, String currentEngagementId) {
    FoldState state = new FoldState();
    for (SelectedFact fact : evidence) {
      boolean isCurrent = fact.engagementId().equals(currentEngagementId);
      switch (fact) {
        case FindingOccurrence occurrence -> applyOccurrence(state, isCurrent);
        case TestAttempt attempt -> applyAttempt(state, attempt, isCurrent);
      }
    }
    return state;
  }

  private static void applyOccurrence(FoldState state, boolean isCurrent) {
    if (state.firstOccurrenceIsCurrent == null) {
      state.firstOccurrenceIsCurrent = isCurrent;
    } else if (isCurrent && state.exposure == LastVerifiedExposure.VERIFIED_RESOLVED) {
      state.currentRegressed = true;
    } else if (isCurrent && state.exposure == LastVerifiedExposure.OPEN) {
      state.currentStillOpen = true;
    }
    state.exposure = LastVerifiedExposure.OPEN;
    if (isCurrent) {
      state.lastDecisive = CurrentAssessment.DETECTED;
    }
  }

  private static void applyAttempt(FoldState state, TestAttempt attempt, boolean isCurrent) {
    if (isCompletedNegative(attempt)) {
      if (state.exposure == LastVerifiedExposure.OPEN) {
        state.exposure = LastVerifiedExposure.VERIFIED_RESOLVED;
      }
      // A current negative against already-verified exposure still confirms
      // resolution; only a never-opened history leaves the flag unraised.
      if (isCurrent && state.exposure == LastVerifiedExposure.VERIFIED_RESOLVED) {
        state.currentResolved = true;
      }
      if (isCurrent) {
        state.lastDecisive = CurrentAssessment.NOT_DETECTED;
      }
      return;
    }
    // A completed detected attempt is coverage evidence beside its validated
    // occurrence; incomplete attempts only mark the episode inconclusive.
    if (isCurrent && attempt.executionStatus() != ExecutionStatus.COMPLETED) {
      state.currentInconclusive = true;
    }
  }

  /** Fixed precedence; exactly one bucket, evaluated once after folding. */
  private static EpisodeClassification classify(FoldState state) {
    if (Boolean.TRUE.equals(state.firstOccurrenceIsCurrent)) {
      return EpisodeClassification.NEW;
    }
    if (state.currentRegressed) {
      return EpisodeClassification.REGRESSED;
    }
    if (state.currentResolved) {
      return EpisodeClassification.VERIFIED_RESOLVED;
    }
    if (state.currentStillOpen) {
      return EpisodeClassification.STILL_OPEN;
    }
    if (state.currentInconclusive) {
      return EpisodeClassification.INCONCLUSIVE;
    }
    return EpisodeClassification.NOT_RETESTED;
  }

  /** Decisive current evidence wins by order; incomplete evidence never overrides it. */
  private static CurrentAssessment assessment(FoldState state) {
    if (state.lastDecisive != null) {
      return state.lastDecisive;
    }
    return state.currentInconclusive
        ? CurrentAssessment.INCONCLUSIVE
        : CurrentAssessment.NOT_RETESTED;
  }

  private static void requireValidInputs(String currentEngagementId, List<SelectedFact> facts) {
    if (currentEngagementId == null || currentEngagementId.isBlank()) {
      throw new IllegalArgumentException("the fold requires a nonblank current engagement id");
    }
    Objects.requireNonNull(facts, "facts");
    if (facts.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("the fold rejects null facts");
    }
  }

  /**
   * Every classification derives from one finding's recorded occurrences; the
   * shared immutable baseline they carry is the attempt-matching target.
   */
  private static FindingVerificationBaseline requireSingleFindingBaseline(
      List<SelectedFact> facts) {
    List<FindingOccurrence> occurrences =
        facts.stream()
            .filter(FindingOccurrence.class::isInstance)
            .map(FindingOccurrence.class::cast)
            .toList();
    if (occurrences.isEmpty()) {
      throw new InvalidPostureHistoryException(
          "no finding occurrence exists to derive a classification from");
    }
    List<UUID> findingIds =
        occurrences.stream().map(FindingOccurrence::findingId).distinct().toList();
    if (findingIds.size() > 1) {
      throw new InvalidPostureHistoryException(
          "the fold classifies one finding but received occurrences of " + findingIds);
    }
    FindingOccurrence first = occurrences.get(0);
    return new FindingVerificationBaseline(
        first.tenantId(),
        first.canonicalAssetId(),
        first.verificationKey(),
        first.checkVersion(),
        first.relevantContextHash(),
        first.compatibilityPolicyVersion());
  }

  /** Every reported detection needs its occurrence; coverage evidence never invents one. */
  private static void requireOccurrenceForDetectedAttempts(List<SelectedFact> evidence) {
    for (SelectedFact fact : evidence) {
      if (fact instanceof TestAttempt attempt
          && attempt.executionStatus() == ExecutionStatus.COMPLETED
          && attempt.result() == TestResult.DETECTED
          && !hasOccurrenceInEngagement(evidence, attempt.engagementId())) {
        throw new InvalidPostureHistoryException(
            ("detected attempt %s in engagement %s has no corresponding finding occurrence"
                    + " to classify from")
                .formatted(attempt.sourceEventId(), attempt.engagementId()));
      }
    }
  }

  private static boolean hasOccurrenceInEngagement(
      List<SelectedFact> evidence, String engagementId) {
    return evidence.stream()
        .anyMatch(
            fact ->
                fact instanceof FindingOccurrence
                    && fact.engagementId().equals(engagementId));
  }

  private static boolean isCompatible(TestAttempt attempt, FindingVerificationBaseline baseline) {
    return CoverageCompatibilityPolicyV1.isCompatible(
        new RecordedTestAttempt(
            attempt.tenantId(),
            attempt.sourceEventId(),
            attempt.canonicalAssetId(),
            attempt.verificationKey(),
            attempt.checkVersion(),
            attempt.relevantContextHash(),
            attempt.compatibilityPolicyVersion(),
            attempt.executionStatus(),
            attempt.result()),
        baseline);
  }

  private static boolean isCompletedNegative(TestAttempt attempt) {
    return attempt.executionStatus() == ExecutionStatus.COMPLETED
        && attempt.result() == TestResult.NOT_DETECTED;
  }

  /** Carried fold state; flags are raised only by current-engagement facts. */
  private static final class FoldState {
    private LastVerifiedExposure exposure;
    private Boolean firstOccurrenceIsCurrent;
    private boolean currentRegressed;
    private boolean currentResolved;
    private boolean currentStillOpen;
    private boolean currentInconclusive;
    private CurrentAssessment lastDecisive;
  }
}
