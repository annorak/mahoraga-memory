package dev.mahoraga.memory.boundary;

/**
 * One finalized stream position inside a knowledge boundary: every event of
 * the stream at or below {@code lastDataSequence} is visible. Values come from
 * write-once finalized engagement limits, never from marker presence alone.
 */
public record BoundaryPosition(String sourceStreamId, long lastDataSequence) {

  public BoundaryPosition {
    if (sourceStreamId == null || sourceStreamId.isBlank()) {
      throw new IllegalArgumentException("boundary position requires a nonblank source stream id");
    }
    if (lastDataSequence <= 0) {
      throw new IllegalArgumentException(
          "boundary position for stream %s requires a positive last data sequence"
              .formatted(sourceStreamId));
    }
  }
}
