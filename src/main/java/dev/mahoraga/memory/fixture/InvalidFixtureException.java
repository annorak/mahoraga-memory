package dev.mahoraga.memory.fixture;

/**
 * Rejection of a synthetic fixture bundle at parse or cross-reference time.
 * Messages identify the offending file, event, candidate, or field; they never
 * carry a full source-event payload. A single rejection fails the whole bundle
 * before any database ingestion occurs.
 */
public class InvalidFixtureException extends RuntimeException {

  public InvalidFixtureException(String message) {
    super(message);
  }

  public InvalidFixtureException(String message, Throwable cause) {
    super(message, cause);
  }
}
