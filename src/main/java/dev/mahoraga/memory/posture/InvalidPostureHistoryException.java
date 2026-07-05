package dev.mahoraga.memory.posture;

/**
 * A selected fact history that no valid classification can be derived from,
 * such as no finding occurrence at all, mixed finding identities, or a matched
 * detected attempt without its required occurrence. The fold fails explicitly
 * rather than guessing a seventh outcome.
 */
public final class InvalidPostureHistoryException extends RuntimeException {

  public InvalidPostureHistoryException(String message) {
    super(message);
  }
}
