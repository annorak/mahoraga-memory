package dev.mahoraga.memory.ingest;

import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.ingest.SourceEventConflictException.Reason;
import dev.mahoraga.memory.ingest.SourceEventInbox.StoredEvent;
import dev.mahoraga.memory.ingest.SourceEventInbox.StreamOwner;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * The single ingestion transaction: verifies the durable stream binding in both
 * directions, deduplicates by source-event identity, persists the immutable
 * inbox row, and runs the caller's domain work on the same handle so first
 * delivery and every derived write commit or roll back together.
 *
 * <p>Validation and canonical hashing happen in the contract layer before this
 * class is called; it accepts only {@link CanonicalSourceEvent}. Database
 * unique constraints remain the final race guard: the MVP is sequential, and an
 * escaped race propagates as a storage failure that rolls back rather than
 * being retried or reinterpreted here.
 */
public final class IngestionTransaction {

  /**
   * Domain work executed on first delivery inside the ingestion transaction.
   * Implementations must be deterministic and database-only through the given
   * handle — no network, filesystem, messaging, or other external side effects
   * — because the transaction can roll back and a committed exact retry never
   * reruns the work. This is a narrow transaction-participation boundary for
   * the concrete handlers of later capabilities, not a general plugin API.
   */
  @FunctionalInterface
  public interface DatabaseWork {
    void execute(Handle handle);
  }

  private final Jdbi jdbi;
  private final SourceEventInbox inbox;

  @Inject
  public IngestionTransaction(Jdbi jdbi, SourceEventInbox inbox) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    this.inbox = Objects.requireNonNull(inbox, "inbox");
  }

  public IngestResult ingest(
      TrustedContext context, CanonicalSourceEvent canonical, DatabaseWork work) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(canonical, "canonical");
    Objects.requireNonNull(work, "work");
    return jdbi.inTransaction(handle -> ingestOnHandle(handle, context, canonical, work));
  }

  private IngestResult ingestOnHandle(
      Handle handle, TrustedContext context, CanonicalSourceEvent canonical, DatabaseWork work) {
    SourceEvent event = canonical.event();
    verifyStreamBinding(handle, context, event);
    Optional<StoredEvent> stored =
        inbox.findEvent(handle, context.tenantId(), event.sourceEventId());
    if (stored.isPresent()) {
      requireExactRetry(stored.get(), context, canonical);
      return IngestResult.NO_OP;
    }
    if (inbox.isPositionOccupied(
        handle, context.tenantId(), event.sourceStreamId(), event.sourceSequence())) {
      throw new SourceEventConflictException(
          Reason.STREAM_POSITION,
          "source event %s for tenant %s: position %d in stream %s is already occupied by a different event"
              .formatted(
                  event.sourceEventId(),
                  context.tenantId(),
                  event.sourceSequence(),
                  event.sourceStreamId()));
    }
    inbox.insertEvent(handle, context, canonical);
    work.execute(handle);
    return IngestResult.ACCEPTED;
  }

  /**
   * Both directions must hold: the engagement's bound stream is this stream,
   * and this stream's durable owner is this tenant and engagement. Ownership
   * conflict messages never reveal the actual owner.
   */
  private void verifyStreamBinding(Handle handle, TrustedContext context, SourceEvent event) {
    String sourceStreamId = event.sourceStreamId();
    inbox.insertEngagementIfAbsent(handle, context, sourceStreamId);
    Optional<String> boundStream = inbox.findEngagementStream(handle, context);
    if (boundStream.isPresent() && !boundStream.get().equals(sourceStreamId)) {
      throw new SourceEventConflictException(
          Reason.ENGAGEMENT_BINDING,
          "source event %s for tenant %s: engagement %s is already bound to a different source stream"
              .formatted(event.sourceEventId(), context.tenantId(), context.engagementId()));
    }
    StreamOwner owner =
        inbox
            .findStreamOwner(handle, sourceStreamId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "stream " + sourceStreamId + " has no owner after binding insert"));
    if (!owner.equals(new StreamOwner(context.tenantId(), context.engagementId()))) {
      throw new SourceEventConflictException(
          Reason.STREAM_OWNERSHIP,
          "source event %s for tenant %s: stream %s is already bound to another tenant or engagement"
              .formatted(event.sourceEventId(), context.tenantId(), sourceStreamId));
    }
  }

  /** A retry is exact only when hash and the verified binding all match. */
  private static void requireExactRetry(
      StoredEvent stored, TrustedContext context, CanonicalSourceEvent canonical) {
    SourceEvent event = canonical.event();
    boolean isExactRetry =
        stored.canonicalSourceHash().equals(canonical.canonicalSourceHash())
            && stored.engagementId().equals(context.engagementId())
            && stored.sourceStreamId().equals(event.sourceStreamId())
            && stored.sourceSequence() == event.sourceSequence();
    if (!isExactRetry) {
      throw new SourceEventConflictException(
          Reason.EVENT_CONTENT,
          "source event %s for tenant %s already exists with different content or binding"
              .formatted(event.sourceEventId(), context.tenantId()));
    }
  }
}
