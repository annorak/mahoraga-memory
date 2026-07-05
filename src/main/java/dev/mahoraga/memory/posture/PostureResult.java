package dev.mahoraga.memory.posture;

import java.util.Objects;

/**
 * The three separate posture dimensions for one finding at one boundary. Last
 * verified exposure carries what evidence last proved; the current assessment
 * describes only the explicit current engagement; the episode classification
 * is exactly one of six mutually exclusive buckets. Keeping the dimensions
 * separate preserves composite histories without inventing a seventh bucket.
 */
public record PostureResult(
    LastVerifiedExposure lastVerifiedExposure,
    CurrentAssessment currentAssessment,
    EpisodeClassification episodeClassification) {

  public PostureResult {
    Objects.requireNonNull(lastVerifiedExposure, "lastVerifiedExposure");
    Objects.requireNonNull(currentAssessment, "currentAssessment");
    Objects.requireNonNull(episodeClassification, "episodeClassification");
  }

  /** What the ordered evidence last verified about exposure. */
  public enum LastVerifiedExposure {
    OPEN,
    VERIFIED_RESOLVED
  }

  /** What the current engagement itself established. */
  public enum CurrentAssessment {
    DETECTED,
    NOT_DETECTED,
    NOT_RETESTED,
    INCONCLUSIVE
  }

  /** The single engagement-episode bucket, in fixed precedence order. */
  public enum EpisodeClassification {
    NEW,
    REGRESSED,
    VERIFIED_RESOLVED,
    STILL_OPEN,
    INCONCLUSIVE,
    NOT_RETESTED
  }
}
