package dev.mahoraga.memory.contract;

/**
 * Rejection of external source-event input. Messages identify the offending
 * field and, when safely parsed, the event ID; they never carry the full
 * payload.
 */
public class InvalidSourceEventException extends RuntimeException {

  public InvalidSourceEventException(String message) {
    super(message);
  }

  public InvalidSourceEventException(String message, Throwable cause) {
    super(message, cause);
  }
}
