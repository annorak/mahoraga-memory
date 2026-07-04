package dev.mahoraga.memory.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.Handle;

/**
 * Concrete SQL for the source-event inbox: engagement/stream binding rows and
 * immutable {@code source_events} rows. Every statement is parameterized and
 * tenant-qualified, except the stream-ownership lookup, which is deliberately
 * global because a stream can never be rebound across tenants or engagements.
 */
public final class SourceEventInbox {

  /** The binding and content identity recorded for an already stored event. */
  record StoredEvent(
      String engagementId,
      String sourceStreamId,
      long sourceSequence,
      String canonicalSourceHash) {}

  /** The tenant and engagement a stream is durably bound to. */
  record StreamOwner(String tenantId, String engagementId) {}

  private final ObjectMapper objectMapper;

  @Inject
  public SourceEventInbox(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  void insertEngagementIfAbsent(Handle handle, TrustedContext context, String sourceStreamId) {
    handle
        .createUpdate(
            "INSERT INTO engagements (tenant_id, engagement_id, source_stream_id)"
                + " VALUES (:tenantId, :engagementId, :sourceStreamId)"
                + " ON CONFLICT DO NOTHING")
        .bind("tenantId", context.tenantId())
        .bind("engagementId", context.engagementId())
        .bind("sourceStreamId", sourceStreamId)
        .execute();
  }

  Optional<StreamOwner> findStreamOwner(Handle handle, String sourceStreamId) {
    return handle
        .createQuery(
            "SELECT tenant_id, engagement_id FROM engagements"
                + " WHERE source_stream_id = :sourceStreamId")
        .bind("sourceStreamId", sourceStreamId)
        .map(
            (rs, ctx) ->
                new StreamOwner(rs.getString("tenant_id"), rs.getString("engagement_id")))
        .findOne();
  }

  Optional<String> findEngagementStream(Handle handle, TrustedContext context) {
    return handle
        .createQuery(
            "SELECT source_stream_id FROM engagements"
                + " WHERE tenant_id = :tenantId AND engagement_id = :engagementId")
        .bind("tenantId", context.tenantId())
        .bind("engagementId", context.engagementId())
        .mapTo(String.class)
        .findOne();
  }

  Optional<StoredEvent> findEvent(Handle handle, String tenantId, String sourceEventId) {
    return handle
        .createQuery(
            "SELECT engagement_id, source_stream_id, source_sequence, canonical_source_hash"
                + " FROM source_events"
                + " WHERE tenant_id = :tenantId AND source_event_id = :sourceEventId")
        .bind("tenantId", tenantId)
        .bind("sourceEventId", sourceEventId)
        .map(
            (rs, ctx) ->
                new StoredEvent(
                    rs.getString("engagement_id"),
                    rs.getString("source_stream_id"),
                    rs.getLong("source_sequence"),
                    rs.getString("canonical_source_hash")))
        .findOne();
  }

  boolean isPositionOccupied(
      Handle handle, String tenantId, String sourceStreamId, long sourceSequence) {
    return handle
        .createQuery(
            "SELECT EXISTS (SELECT 1 FROM source_events"
                + " WHERE tenant_id = :tenantId AND source_stream_id = :sourceStreamId"
                + " AND source_sequence = :sourceSequence)")
        .bind("tenantId", tenantId)
        .bind("sourceStreamId", sourceStreamId)
        .bind("sourceSequence", sourceSequence)
        .mapTo(Boolean.class)
        .one();
  }

  void insertEvent(Handle handle, TrustedContext context, CanonicalSourceEvent canonical) {
    SourceEvent event = canonical.event();
    handle
        .createUpdate(
            """
            INSERT INTO source_events (tenant_id, engagement_id, source_event_id, event_type,
              source_stream_id, source_sequence, schema_version, occurred_at, payload,
              canonical_source_hash)
            VALUES (:tenantId, :engagementId, :sourceEventId, :eventType, :sourceStreamId,
              :sourceSequence, :schemaVersion, :occurredAt, CAST(:payload AS jsonb),
              :canonicalSourceHash)
            """)
        .bind("tenantId", context.tenantId())
        .bind("engagementId", context.engagementId())
        .bind("sourceEventId", event.sourceEventId())
        .bind("eventType", event.eventType().wireValue())
        .bind("sourceStreamId", event.sourceStreamId())
        .bind("sourceSequence", event.sourceSequence())
        .bind("schemaVersion", event.schemaVersion())
        .bind("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
        .bind("payload", payloadJson(canonical))
        .bind("canonicalSourceHash", canonical.canonicalSourceHash())
        .execute();
  }

  /**
   * The payload subtree of the already-canonical bytes, so the stored JSON has
   * exactly one serialization source of truth.
   */
  private String payloadJson(CanonicalSourceEvent canonical) {
    try {
      return objectMapper.readTree(canonical.canonicalJson()).get("payload").toString();
    } catch (IOException e) {
      throw new IllegalStateException(
          "canonical JSON is unreadable for source event " + canonical.event().sourceEventId(), e);
    }
  }
}
