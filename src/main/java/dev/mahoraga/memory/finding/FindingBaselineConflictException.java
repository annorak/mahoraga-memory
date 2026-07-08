package dev.mahoraga.memory.finding;

/**
 * An observation matched an existing finding identity but disagreed with its
 * recorded four-part verification baseline. The finding is never updated; the
 * whole source event rolls back. Changing a baseline requires a new versioned
 * model. Messages carry stable identifiers, never payload values.
 */
public final class FindingBaselineConflictException extends RuntimeException {

  public FindingBaselineConflictException(String message) {
    super(message);
  }
}
