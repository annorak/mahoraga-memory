package dev.mahoraga.memory.ingest;

import java.util.Objects;

/**
 * A source event that can never be accepted as sent because its identity,
 * stream position, or stream ownership contradicts durable state. Messages
 * carry stable identifiers only, never payload content or credentials.
 */
public final class SourceEventConflictException extends RuntimeException {

  /** Which durable contract the event contradicts. */
  public enum Reason {
    /** Same tenant and event ID exists with different content or binding. */
    EVENT_CONTENT,
    /** The stream position is already occupied by a different event. */
    STREAM_POSITION,
    /** The stream is already bound to another tenant or engagement. */
    STREAM_OWNERSHIP,
    /** The engagement is already bound to a different stream. */
    ENGAGEMENT_BINDING
  }

  private final Reason reason;

  public SourceEventConflictException(Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
