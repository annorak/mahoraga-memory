package dev.mahoraga.memory.boundary;

/**
 * Rejection of a source event that violates the durable completion protocol of
 * its stream: arriving after finalization, exceeding a pending marker's data
 * limit, or presenting a marker below already-recorded positions.
 */
public final class EngagementCompletionConflictException extends RuntimeException {

  public EngagementCompletionConflictException(String message) {
    super(message);
  }
}
