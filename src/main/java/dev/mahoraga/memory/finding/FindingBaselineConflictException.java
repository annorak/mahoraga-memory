package dev.mahoraga.memory.finding;

/**
 * An observation matched an existing finding identity but disagreed with its
 * recorded four-part verification baseline. The finding is never updated; the
 * whole source event rolls back. Evolving a baseline requires a future
 * versioned model. Messages carry stable identifiers, never payload values.
 */
public final class FindingBaselineConflictException extends RuntimeException {

  public FindingBaselineConflictException(String message) {
    super(message);
  }
}
