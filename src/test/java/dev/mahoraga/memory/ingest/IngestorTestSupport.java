package dev.mahoraga.memory.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.boundary.EngagementCompletionHandler;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.coverage.TestAttemptService;
import dev.mahoraga.memory.database.TestDatabase;
import dev.mahoraga.memory.finding.FindingIdentityService;
import dev.mahoraga.memory.identity.AssetIdentityService;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;

/**
 * Shared migrated-database wiring and event builders for the ingestor and
 * completion tests. Every event goes through the real codec and the fully
 * composed {@link SourceEventIngestor}, so tests exercise the production path;
 * a failing fault hook may be supplied to prove single-transaction rollback.
 */
public final class IngestorTestSupport {

  public static final ObjectMapper MAPPER = Jackson.newObjectMapper();
  private static final String OCCURRED_AT = "2026-01-01T10:00:00Z";

  public final Jdbi jdbi;
  public final SourceEventIngestor ingestor;
  private final SourceEventCodec codec;

  private IngestorTestSupport(Jdbi jdbi, SourceEventIngestor.IngestionFaultHook faultHook) {
    this.jdbi = jdbi;
    AssetIdentityService assetService = new AssetIdentityService(MAPPER);
    this.ingestor =
        new SourceEventIngestor(
            new IngestionTransaction(jdbi, new SourceEventInbox()),
            assetService,
            new FindingIdentityService(assetService),
            new TestAttemptService(assetService),
            new EngagementCompletionHandler(),
            faultHook);
    this.codec =
        new SourceEventCodec(MAPPER, new SourceEventValidator(BaseValidator.newValidator()));
  }

  public static IngestorTestSupport forDatabase(String databaseName) throws SQLException {
    return forDatabase(databaseName, SourceEventIngestor.NO_FAULTS);
  }

  public static IngestorTestSupport forDatabase(
      String databaseName, SourceEventIngestor.IngestionFaultHook faultHook) throws SQLException {
    String url = TestDatabase.ensureDatabase(databaseName);
    Flyway.configure()
        .dataSource(url, TestDatabase.username(), TestDatabase.password())
        .load()
        .migrate();
    return new IngestorTestSupport(
        Jdbi.create(url, TestDatabase.username(), TestDatabase.password()), faultHook);
  }

  /** A minimal valid data event: one authoritative Deployment observation. */
  public CanonicalSourceEvent assetEvent(String eventId, String streamId, long sequence) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cluster_id", "cluster-demo");
    payload.put("resource_kind", "Deployment");
    payload.put("resource_uid", "deploy-1");
    payload.put("pod_uid", "pod-1");
    return decode(envelope(eventId, "asset_observation", streamId, sequence, payload));
  }

  public CanonicalSourceEvent findingEvent(String eventId, String streamId, long sequence) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cluster_id", "cluster-demo");
    payload.put("resource_kind", "Deployment");
    payload.put("resource_uid", "deploy-1");
    payload.put("vuln_class", "xss");
    payload.put("normalized_location_signature", "route:/login");
    payload.put("verification_key", "check-xss-1");
    payload.put("check_version", "1.0");
    payload.put("relevant_context", relevantContext());
    payload.put("compatibility_policy_version", 1);
    return decode(envelope(eventId, "finding_observation", streamId, sequence, payload));
  }

  public CanonicalSourceEvent attemptEvent(String eventId, String streamId, long sequence) {
    return attemptEvent(eventId, streamId, sequence, OCCURRED_AT);
  }

  /** Variant with an explicit occurred_at for backdated-chronology cases. */
  public CanonicalSourceEvent attemptEvent(
      String eventId, String streamId, long sequence, String occurredAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cluster_id", "cluster-demo");
    payload.put("resource_kind", "Deployment");
    payload.put("resource_uid", "deploy-1");
    payload.put("verification_key", "check-xss-1");
    payload.put("check_version", "1.0");
    payload.put("relevant_context", relevantContext());
    payload.put("execution_status", "completed");
    payload.put("result", "not_detected");
    payload.put("compatibility_policy_version", 1);
    return decode(envelope(eventId, "test_attempt", streamId, sequence, occurredAt, payload));
  }

  /** A valid marker sits at {@code lastDataSequence + 1} by the contract rule. */
  public CanonicalSourceEvent markerEvent(String eventId, String streamId, long lastDataSequence) {
    return markerEventAt(eventId, streamId, lastDataSequence + 1, lastDataSequence);
  }

  /** Raw variant so tests can present contract-violating marker positions. */
  public CanonicalSourceEvent markerEventAt(
      String eventId, String streamId, long sequence, long lastDataSequence) {
    return decode(
        envelope(
            eventId,
            "engagement_completed",
            streamId,
            sequence,
            Map.of("last_data_sequence", lastDataSequence)));
  }

  public int count(String table, String tenantId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT count(*) FROM " + table + " WHERE tenant_id = :tenantId")
                .bind("tenantId", tenantId)
                .mapTo(Integer.class)
                .one());
  }

  public boolean eventExists(String tenantId, String sourceEventId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT EXISTS (SELECT 1 FROM source_events WHERE tenant_id = :tenantId"
                        + " AND source_event_id = :sourceEventId)")
                .bind("tenantId", tenantId)
                .bind("sourceEventId", sourceEventId)
                .mapTo(Boolean.class)
                .one());
  }

  /** Null while the engagement is not finalized. */
  public Long boundary(String tenantId, String engagementId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT last_data_sequence FROM engagements WHERE tenant_id = :tenantId"
                        + " AND engagement_id = :engagementId")
                .bind("tenantId", tenantId)
                .bind("engagementId", engagementId)
                .mapTo(Long.class)
                .one());
  }

  private static Map<String, Object> relevantContext() {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("protocol", "https");
    context.put("port", 443);
    context.put("normalized_route", "/login");
    context.put("parameters", Map.of());
    context.put("is_address_bound", false);
    return context;
  }

  private static Map<String, Object> envelope(
      String eventId, String eventType, String streamId, long sequence,
      Map<String, Object> payload) {
    return envelope(eventId, eventType, streamId, sequence, OCCURRED_AT, payload);
  }

  private static Map<String, Object> envelope(
      String eventId, String eventType, String streamId, long sequence, String occurredAt,
      Map<String, Object> payload) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("source_event_id", eventId);
    envelope.put("event_type", eventType);
    envelope.put("source_stream_id", streamId);
    envelope.put("source_sequence", sequence);
    envelope.put("schema_version", 1);
    envelope.put("occurred_at", occurredAt);
    envelope.put("payload", payload);
    return envelope;
  }

  private CanonicalSourceEvent decode(Map<String, Object> envelope) {
    try {
      return codec.decode(MAPPER.writeValueAsString(envelope));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("test envelope failed to serialize", e);
    }
  }
}
