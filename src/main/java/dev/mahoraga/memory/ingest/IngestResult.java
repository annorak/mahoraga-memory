package dev.mahoraga.memory.ingest;

/**
 * Binary outcome of one ingestion transaction: the event was persisted now with
 * its domain work, or it was an exact committed retry and nothing ran. Conflicts
 * are never results; they throw {@link SourceEventConflictException}.
 */
public enum IngestResult {
  ACCEPTED,
  NO_OP
}
