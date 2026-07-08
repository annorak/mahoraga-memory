package dev.mahoraga.memory.ingest;

/**
 * Outcome of one ingestion transaction: the event and its domain work were
 * committed, or an exact committed retry skipped the domain work. Conflicts
 * throw {@link SourceEventConflictException} instead of returning a result.
 */
public enum IngestResult {
  ACCEPTED,
  NO_OP
}
