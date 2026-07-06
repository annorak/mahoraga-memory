package dev.mahoraga.memory.boundary;

import dev.mahoraga.memory.contract.TrustedContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Handle;

/**
 * Verifies that every position of a requested boundary is exactly the
 * write-once finalized limit of one of the trusted tenant's streams. Report
 * and planner queries share this guard, so an unknown stream, an unfinalized
 * engagement, or a partial position rejects before any fact is selected.
 */
public final class FinalizedBoundaries {

  private FinalizedBoundaries() {}

  /**
   * Returns the engagement bound to each boundary stream, in boundary order.
   * Unknown, unfinalized, or partial positions reject.
   */
  public static Map<String, String> requireFinalized(
      Handle handle, TrustedContext context, KnowledgeBoundary boundary) {
    List<String> streamIds =
        boundary.positions().stream().map(BoundaryPosition::sourceStreamId).toList();
    Map<String, StreamBinding> bindings = new HashMap<>();
    handle
        .createQuery(
            "SELECT source_stream_id, engagement_id, last_data_sequence FROM engagements"
                + " WHERE tenant_id = :tenantId AND source_stream_id = ANY(:streamIds)")
        .bind("tenantId", context.tenantId())
        .bindArray("streamIds", String.class, streamIds)
        .map(
            (rs, ctx) ->
                new StreamBinding(
                    rs.getString("source_stream_id"),
                    rs.getString("engagement_id"),
                    rs.getObject("last_data_sequence", Long.class)))
        .forEach(binding -> bindings.put(binding.sourceStreamId(), binding));
    Map<String, String> engagementByStream = new LinkedHashMap<>();
    for (BoundaryPosition position : boundary.positions()) {
      StreamBinding binding = bindings.get(position.sourceStreamId());
      requireFinalizedAt(binding, position);
      engagementByStream.put(position.sourceStreamId(), binding.engagementId());
    }
    return engagementByStream;
  }

  private static void requireFinalizedAt(StreamBinding binding, BoundaryPosition position) {
    String streamId = position.sourceStreamId();
    if (binding == null) {
      throw new IllegalArgumentException(
          "stream %s is not an engagement stream of the requesting tenant".formatted(streamId));
    }
    if (binding.lastDataSequence() == null) {
      throw new IllegalArgumentException(
          "stream %s is not finalized; no knowledge boundary exists for it".formatted(streamId));
    }
    if (binding.lastDataSequence() != position.lastDataSequence()) {
      throw new IllegalArgumentException(
          "stream %s is finalized at position %d, not the requested %d"
              .formatted(streamId, binding.lastDataSequence(), position.lastDataSequence()));
    }
  }

  /** One tenant-owned stream row: its engagement and nullable finalized limit. */
  private record StreamBinding(String sourceStreamId, String engagementId, Long lastDataSequence) {}
}
