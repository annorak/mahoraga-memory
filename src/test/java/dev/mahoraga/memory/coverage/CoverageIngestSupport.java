package dev.mahoraga.memory.coverage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.contract.SourcePayload.FindingObservation;
import dev.mahoraga.memory.contract.SourcePayload.TestAttempt;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.database.TestDatabase;
import dev.mahoraga.memory.finding.FindingId;
import dev.mahoraga.memory.finding.FindingIdentityService;
import dev.mahoraga.memory.identity.AssetIdentityService;
import dev.mahoraga.memory.ingest.IngestionFaultHook;
import dev.mahoraga.memory.ingest.IngestionTransaction;
import dev.mahoraga.memory.ingest.SourceEventInbox;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;

/**
 * Shared migrated-database wiring, source-event builders, and row readers for
 * the coverage tests. Attempts and findings ingest through the real codec and
 * ingestion transaction so every test exercises the production path.
 */
final class CoverageIngestSupport {

  static final ObjectMapper MAPPER = Jackson.newObjectMapper();
  static final String OCCURRED_AT = "2026-01-01T10:00:00Z";

  final Jdbi jdbi;
  final IngestionTransaction transaction;
  final TestAttemptService service;
  final FindingIdentityService findingService;
  private final SourceEventCodec codec;

  private CoverageIngestSupport(Jdbi jdbi) {
    this.jdbi = jdbi;
    this.transaction = new IngestionTransaction(jdbi, new SourceEventInbox());
    AssetIdentityService assetService =
        new AssetIdentityService(MAPPER, IngestionFaultHook.NO_FAULTS);
    this.service = new TestAttemptService(assetService, IngestionFaultHook.NO_FAULTS);
    this.findingService =
        new FindingIdentityService(assetService, IngestionFaultHook.NO_FAULTS);
    this.codec =
        new SourceEventCodec(MAPPER, new SourceEventValidator(BaseValidator.newValidator()));
  }

  static CoverageIngestSupport forDatabase(String databaseName) throws SQLException {
    String url = TestDatabase.ensureDatabase(databaseName);
    Flyway.configure()
        .dataSource(url, TestDatabase.username(), TestDatabase.password())
        .load()
        .migrate();
    return new CoverageIngestSupport(
        Jdbi.create(url, TestDatabase.username(), TestDatabase.password()));
  }

  RecordedTestAttempt ingestAttempt(
      TrustedContext context,
      String streamId,
      long sequence,
      String eventId,
      Map<String, Object> payloadOverrides) {
    return ingestCanonical(context, attemptEvent(eventId, streamId, sequence, payloadOverrides));
  }

  RecordedTestAttempt ingestCanonical(TrustedContext context, CanonicalSourceEvent canonical) {
    AtomicReference<RecordedTestAttempt> recorded = new AtomicReference<>();
    transaction.ingest(
        context,
        canonical,
        handle ->
            recorded.set(
                service.recordTestAttempt(
                    handle,
                    context,
                    canonical.event().sourceEventId(),
                    attemptPayload(canonical))));
    return recorded.get();
  }

  FindingId ingestFinding(
      TrustedContext context,
      String streamId,
      long sequence,
      String eventId,
      Map<String, Object> payloadOverrides) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("cluster_id", "cluster-demo");
    payload.put("resource_kind", "Deployment");
    payload.put("resource_uid", "deploy-1");
    payload.put("vuln_class", "xss");
    payload.put("normalized_location_signature", "route:/login");
    payload.put("verification_key", "check-xss-1");
    payload.put("check_version", "1.0");
    payload.put("relevant_context", contextMap(443, false, null, parameters(false)));
    payload.put("compatibility_policy_version", 1);
    payload.putAll(payloadOverrides);
    CanonicalSourceEvent canonical =
        decode(envelope(eventId, "finding_observation", streamId, sequence, payload));
    AtomicReference<FindingId> findingId = new AtomicReference<>();
    transaction.ingest(
        context,
        canonical,
        handle ->
            findingId.set(
                findingService.recordFindingObservation(
                    handle, context, eventId, (FindingObservation) canonical.event().payload())));
    return findingId.get();
  }

  CanonicalSourceEvent attemptEvent(
      String eventId, String streamId, long sequence, Map<String, Object> payloadOverrides) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("cluster_id", "cluster-demo");
    payload.put("resource_kind", "Deployment");
    payload.put("resource_uid", "deploy-1");
    payload.put("verification_key", "check-xss-1");
    payload.put("check_version", "1.0");
    payload.put("relevant_context", contextMap(443, false, null, parameters(false)));
    payload.put("execution_status", "completed");
    payload.put("result", "not_detected");
    payload.put("compatibility_policy_version", 1);
    payload.putAll(payloadOverrides);
    if (payload.get("result") == null) {
      payload.remove("result");
    }
    return decode(envelope(eventId, "test_attempt", streamId, sequence, payload));
  }

  static TestAttempt attemptPayload(CanonicalSourceEvent canonical) {
    return (TestAttempt) canonical.event().payload();
  }

  static Map<String, Object> outcomeOverrides(String status, String result) {
    Map<String, Object> overrides = new HashMap<>();
    overrides.put("execution_status", status);
    overrides.put("result", result);
    return overrides;
  }

  static Map<String, Object> contextMap(
      int port, boolean isAddressBound, String targetAddress, Map<String, Object> parameters) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("protocol", "https");
    context.put("port", port);
    context.put("normalized_route", "/login");
    context.put("parameters", parameters);
    context.put("is_address_bound", isAddressBound);
    if (targetAddress != null) {
      context.put("target_address", targetAddress);
    }
    return context;
  }

  static Map<String, Object> parameters(boolean reversedOrder) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    if (reversedOrder) {
      parameters.put("payload_set", "standard");
      parameters.put("depth", 2);
    } else {
      parameters.put("depth", 2);
      parameters.put("payload_set", "standard");
    }
    return parameters;
  }

  List<RecordedTestAttempt> findCompatible(TrustedContext context, FindingId findingId) {
    return jdbi.withHandle(handle -> service.findCompatibleAttempts(handle, context, findingId));
  }

  List<RecordedTestAttempt> findResolving(TrustedContext context, FindingId findingId) {
    return jdbi.withHandle(handle -> service.findResolvingEvidence(handle, context, findingId));
  }

  int count(String table, String tenantId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT count(*) FROM " + table + " WHERE tenant_id = :tenantId")
                .bind("tenantId", tenantId)
                .mapTo(Integer.class)
                .one());
  }

  Map<String, Object> attemptRow(String tenantId, String sourceEventId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT canonical_asset_id, relevant_context_hash, execution_status, result"
                        + " FROM test_attempts WHERE tenant_id = :tenantId"
                        + " AND source_event_id = :sourceEventId")
                .bind("tenantId", tenantId)
                .bind("sourceEventId", sourceEventId)
                .mapToMap()
                .one());
  }

  UUID assetId(String tenantId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT canonical_asset_id FROM assets WHERE tenant_id = :tenantId")
                .bind("tenantId", tenantId)
                .mapTo(UUID.class)
                .one());
  }

  String findingColumn(String tenantId, FindingId findingId, String column) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT " + column + " FROM findings WHERE tenant_id = :tenantId"
                        + " AND finding_id = :findingId")
                .bind("tenantId", tenantId)
                .bind("findingId", findingId.value())
                .mapTo(String.class)
                .one());
  }

  private static Map<String, Object> envelope(
      String eventId, String eventType, String streamId, long sequence,
      Map<String, Object> payload) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("source_event_id", eventId);
    envelope.put("event_type", eventType);
    envelope.put("source_stream_id", streamId);
    envelope.put("source_sequence", sequence);
    envelope.put("schema_version", 1);
    envelope.put("occurred_at", OCCURRED_AT);
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
