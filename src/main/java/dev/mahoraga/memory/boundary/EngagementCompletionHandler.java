package dev.mahoraga.memory.boundary;

import dev.mahoraga.memory.contract.EventType;
import dev.mahoraga.memory.contract.SourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.ingest.IngestionFaultHook;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.Handle;

/**
 * The durable completion protocol for one bound stream: data positions
 * {@code 1..N} followed by one marker at {@code N+1} whose payload names N. A
 * marker may commit as pending while positions are missing; the finalized
 * boundary is only the write-once transition of
 * {@code engagements.last_data_sequence} from null to N after a set-based
 * contiguity check. Every decision rereads persisted marker and position rows,
 * so restart needs no in-memory recovery. Both operations run on the caller's
 * active ingestion handle and never open their own transaction.
 */
public final class EngagementCompletionHandler {

  private final IngestionFaultHook faultHook;

  @Inject
  public EngagementCompletionHandler(IngestionFaultHook faultHook) {
    this.faultHook = Objects.requireNonNull(faultHook, "faultHook");
  }

  /**
   * Rejects events the stream can no longer accept: anything after
   * finalization, a data position beyond a pending marker's limit (the marker
   * already occupies {@code N+1}), and a marker whose slot is below an
   * already-recorded position. Runs before the domain handler, so a rejection
   * rolls back the current source event with no derived work.
   */
  public void requireStreamAcceptsEvent(Handle handle, TrustedContext context, SourceEvent event) {
    if (readFinalizedBoundary(handle, context) != null) {
      throw new EngagementCompletionConflictException(
          "source event %s for tenant %s: engagement %s is finalized and accepts no new events"
              .formatted(event.sourceEventId(), context.tenantId(), context.engagementId()));
    }
    if (event.eventType() == EventType.ENGAGEMENT_COMPLETED) {
      requireNoPositionBeyondMarkerSlot(handle, context, event);
      return;
    }
    Optional<Long> pendingLimit = readMarkerLimit(handle, context, event.sourceStreamId());
    if (pendingLimit.isPresent() && event.sourceSequence() > pendingLimit.get()) {
      throw new EngagementCompletionConflictException(
          ("source event %s for tenant %s: position %d in stream %s exceeds the pending"
                  + " completion limit %d")
              .formatted(
                  event.sourceEventId(),
                  context.tenantId(),
                  event.sourceSequence(),
                  event.sourceStreamId(),
                  pendingLimit.get()));
    }
  }

  /**
   * Finalizes after the domain handler when the stream has a marker and every
   * data position {@code 1..N} exists. Running for every accepted event lets a
   * later gap fill finalize in its own transaction without marker replay.
   */
  public void reevaluateCompletion(Handle handle, TrustedContext context, SourceEvent event) {
    Optional<Long> markerLimit = readMarkerLimit(handle, context, event.sourceStreamId());
    if (markerLimit.isEmpty()) {
      return;
    }
    long lastDataSequence = markerLimit.get();
    // Positions are unique and positive, so exactly N rows at or below N means
    // 1..N is contiguous; the single marker sits at N+1 and never counts.
    long present =
        handle
            .createQuery(
                "SELECT count(*) FROM source_events WHERE tenant_id = :tenantId"
                    + " AND source_stream_id = :streamId AND source_sequence <= :limit")
            .bind("tenantId", context.tenantId())
            .bind("streamId", event.sourceStreamId())
            .bind("limit", lastDataSequence)
            .mapTo(Long.class)
            .one();
    if (present < lastDataSequence) {
      return;
    }
    finalizeEngagement(handle, context, lastDataSequence);
  }

  /** The marker occupies {@code N+1}; any recorded position above it disproves its claim. */
  private void requireNoPositionBeyondMarkerSlot(
      Handle handle, TrustedContext context, SourceEvent event) {
    boolean hasPositionBeyondSlot =
        handle
            .createQuery(
                "SELECT EXISTS (SELECT 1 FROM source_events WHERE tenant_id = :tenantId"
                    + " AND source_stream_id = :streamId AND source_sequence > :markerSequence)")
            .bind("tenantId", context.tenantId())
            .bind("streamId", event.sourceStreamId())
            .bind("markerSequence", event.sourceSequence())
            .mapTo(Boolean.class)
            .one();
    if (hasPositionBeyondSlot) {
      throw new EngagementCompletionConflictException(
          ("source event %s for tenant %s: stream %s already holds a position beyond the"
                  + " completion marker at %d")
              .formatted(
                  event.sourceEventId(),
                  context.tenantId(),
                  event.sourceStreamId(),
                  event.sourceSequence()));
    }
  }

  /** Null until finalized; the engagement row exists because the binding insert precedes work. */
  private Long readFinalizedBoundary(Handle handle, TrustedContext context) {
    return handle
        .createQuery(
            "SELECT last_data_sequence FROM engagements WHERE tenant_id = :tenantId"
                + " AND engagement_id = :engagementId")
        .bind("tenantId", context.tenantId())
        .bind("engagementId", context.engagementId())
        .mapTo(Long.class)
        .one();
  }

  private Optional<Long> readMarkerLimit(Handle handle, TrustedContext context, String streamId) {
    return handle
        .createQuery(
            "SELECT (payload ->> 'last_data_sequence')::bigint FROM source_events"
                + " WHERE tenant_id = :tenantId AND source_stream_id = :streamId"
                + " AND event_type = :markerType")
        .bind("tenantId", context.tenantId())
        .bind("streamId", streamId)
        .bind("markerType", EventType.ENGAGEMENT_COMPLETED.wireValue())
        .mapTo(Long.class)
        .findOne();
  }

  /** Write-once: the conditional update must never overwrite a recorded boundary. */
  private void finalizeEngagement(Handle handle, TrustedContext context, long lastDataSequence) {
    int updated =
        handle
            .createUpdate(
                "UPDATE engagements SET last_data_sequence = :lastDataSequence"
                    + " WHERE tenant_id = :tenantId AND engagement_id = :engagementId"
                    + " AND last_data_sequence IS NULL")
            .bind("lastDataSequence", lastDataSequence)
            .bind("tenantId", context.tenantId())
            .bind("engagementId", context.engagementId())
            .execute();
    if (updated != 1) {
      throw new IllegalStateException(
          "engagement %s for tenant %s could not record its completion boundary exactly once"
              .formatted(context.engagementId(), context.tenantId()));
    }
    faultHook.afterStage(IngestionFaultHook.Stage.AFTER_ENGAGEMENT_FINALIZATION_WRITE, handle);
  }
}
