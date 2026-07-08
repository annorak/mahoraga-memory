package dev.mahoraga.memory.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.contract.SourcePayload.AssetObservation;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.database.TestDatabase;
import dev.mahoraga.memory.identity.AssetResolution.Ambiguous;
import dev.mahoraga.memory.identity.AssetResolution.Resolved;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.ingest.IngestionFaultHook;
import dev.mahoraga.memory.ingest.IngestionTransaction;
import dev.mahoraga.memory.ingest.SourceEventInbox;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves resolution policy v1 through the PostgreSQL ingestion transaction:
 * authoritative insert-or-read identity, churn-proof
 * reuse, ambiguous weak collisions with no canonical asset, rejection of
 * UID-less no-candidate input, and atomic rollback with the source event.
 */
class AssetIdentityServiceTest {

  private static final ObjectMapper MAPPER = Jackson.newObjectMapper();
  private static final String OCCURRED_AT = "2026-01-01T10:00:00Z";

  private static Jdbi jdbi;
  private static IngestionTransaction transaction;
  private static AssetIdentityService service;
  private static SourceEventCodec codec;

  @BeforeAll
  static void migrateAndConnect() throws SQLException {
    String url = TestDatabase.ensureDatabase("asset_identity");
    Flyway.configure()
        .dataSource(url, TestDatabase.username(), TestDatabase.password())
        .load()
        .migrate();
    jdbi = Jdbi.create(url, TestDatabase.username(), TestDatabase.password());
    transaction = new IngestionTransaction(jdbi, new SourceEventInbox());
    service = new AssetIdentityService(MAPPER, IngestionFaultHook.NO_FAULTS);
    codec = new SourceEventCodec(MAPPER, new SourceEventValidator(BaseValidator.newValidator()));
  }

  @Test
  void firstAuthoritativeObservationCreatesOneResolvedAsset() {
    TrustedContext context = new TrustedContext("t-auth", "eng-1");
    AssetResolution resolution =
        ingest(context, "stream-auth", 1, "evt-auth-1",
            Map.of(
                "resource_uid", "deploy-1",
                "pod_uid", "pod-a",
                "dns", "auth.demo",
                "banner", "nginx/1.27",
                "labels", Map.of("app", "auth")));

    Resolved resolved = assertInstanceOf(Resolved.class, resolution);
    assertEquals(1, count("assets", "t-auth"));
    assertEquals(1, count("asset_observations", "t-auth"));
    ObservationRow row = observation("t-auth", "evt-auth-1");
    assertEquals("RESOLVED", row.outcome());
    assertEquals(AssetIdentityService.AUTHORITATIVE_BASIS, row.basis());
    assertEquals(resolved.assetId().value(), row.assetId());
    assertEquals("pod-a", row.podUid());
    assertEquals("auth.demo", row.dns());
    assertEquals("nginx/1.27", row.banner());
    assertEquals("object", row.labelsType());
  }

  @Test
  void podChurnPreservesAssetIdentity() {
    TrustedContext context = new TrustedContext("t-churn", "eng-1");
    AssetResolution first =
        ingest(context, "stream-churn", 1, "evt-churn-1",
            Map.of("resource_uid", "deploy-1", "pod_uid", "pod-a", "pod_name", "web-1",
                "ip_address", "10.0.0.1"));
    AssetResolution second =
        ingest(context, "stream-churn", 2, "evt-churn-2",
            Map.of("resource_uid", "deploy-1", "pod_uid", "pod-b", "pod_name", "web-2",
                "ip_address", "10.0.0.9"));

    assertEquals(
        assertInstanceOf(Resolved.class, first).assetId(),
        assertInstanceOf(Resolved.class, second).assetId());
    assertEquals(1, count("assets", "t-churn"));
    assertEquals(2, count("asset_observations", "t-churn"));
  }

  @Test
  void sameKeyInAnotherEngagementReusesTheTenantAsset() {
    AssetResolution first =
        ingest(new TrustedContext("t-engage", "eng-1"), "stream-engage-1", 1, "evt-engage-1",
            Map.of("resource_uid", "deploy-x", "pod_uid", "pod-a"));
    AssetResolution second =
        ingest(new TrustedContext("t-engage", "eng-2"), "stream-engage-2", 1, "evt-engage-2",
            Map.of("resource_uid", "deploy-x", "pod_uid", "pod-b"));

    assertEquals(
        assertInstanceOf(Resolved.class, first).assetId(),
        assertInstanceOf(Resolved.class, second).assetId());
    assertEquals(1, count("assets", "t-engage"));
  }

  @Test
  void sameKeyInAnotherTenantCreatesADistinctAsset() {
    AssetResolution tenantA =
        ingest(new TrustedContext("t-iso-a", "eng-1"), "stream-iso-a", 1, "evt-iso-1",
            Map.of("resource_uid", "deploy-shared", "pod_uid", "pod-a"));
    AssetResolution tenantB =
        ingest(new TrustedContext("t-iso-b", "eng-1"), "stream-iso-b", 1, "evt-iso-2",
            Map.of("resource_uid", "deploy-shared", "pod_uid", "pod-b"));

    assertNotEquals(
        assertInstanceOf(Resolved.class, tenantA).assetId(),
        assertInstanceOf(Resolved.class, tenantB).assetId());
    assertEquals(1, count("assets", "t-iso-a"));
    assertEquals(1, count("assets", "t-iso-b"));
  }

  @Test
  void weakDnsCollisionAcrossAssetsIsAmbiguousWithNoAssetAndNoPostureRows() {
    TrustedContext context = new TrustedContext("t-weak2", "eng-1");
    ingest(context, "stream-weak2", 1, "evt-weak2-1",
        Map.of("resource_uid", "deploy-1", "dns", "shared.demo"));
    ingest(context, "stream-weak2", 2, "evt-weak2-2",
        Map.of("resource_uid", "deploy-2", "dns", "shared.demo"));

    AssetResolution resolution =
        ingest(context, "stream-weak2", 3, "evt-weak2-3",
            Map.of("dns", "shared.demo", "pod_uid", "pod-x"));

    assertInstanceOf(Ambiguous.class, resolution);
    ObservationRow row = observation("t-weak2", "evt-weak2-3");
    assertEquals("AMBIGUOUS", row.outcome());
    assertEquals(AssetIdentityService.WEAK_COLLISION_BASIS, row.basis());
    assertNull(row.assetId());
    assertEquals(2, count("assets", "t-weak2"), "ambiguity must not create an asset");
    assertEquals(0, count("findings", "t-weak2"));
    assertEquals(0, count("test_attempts", "t-weak2"));
  }

  @Test
  void singleWeakLabelCandidateIsStillAmbiguous() {
    TrustedContext context = new TrustedContext("t-weak1", "eng-1");
    ingest(context, "stream-weak1", 1, "evt-weak1-1",
        Map.of("resource_uid", "deploy-1", "labels", Map.of("app", "web")));

    AssetResolution resolution =
        ingest(context, "stream-weak1", 2, "evt-weak1-2",
            Map.of("labels", Map.of("app", "web", "tier", "backend")));

    assertInstanceOf(Ambiguous.class, resolution, "one weak candidate never auto-resolves");
    assertEquals(1, count("assets", "t-weak1"));
  }

  @Test
  void uidlessObservationWithoutCandidateIsRejectedAndFullyRolledBack() {
    TrustedContext context = new TrustedContext("t-none", "eng-1");

    UnsupportedAssetIdentityException rejection =
        assertThrows(
            UnsupportedAssetIdentityException.class,
            () ->
                ingest(context, "stream-none", 1, "evt-none-1",
                    Map.of("dns", "lonely.demo", "pod_uid", "pod-x")));

    assertTrue(rejection.getMessage().contains("evt-none-1"));
    assertTrue(rejection.getMessage().contains("t-none"));
    assertFalse(rejection.getMessage().contains("lonely.demo"), "no evidence values in errors");
    assertEquals(0, count("engagements", "t-none"));
    assertEquals(0, count("source_events", "t-none"));
    assertEquals(0, count("asset_observations", "t-none"));
  }

  @Test
  void authoritativeUidWinsOverCollidingWeakSignals() {
    TrustedContext context = new TrustedContext("t-mixed", "eng-1");
    AssetResolution first =
        ingest(context, "stream-mixed", 1, "evt-mixed-1",
            Map.of("resource_uid", "deploy-1", "dns", "shared.demo"));
    ingest(context, "stream-mixed", 2, "evt-mixed-2",
        Map.of("resource_uid", "deploy-2", "dns", "shared.demo"));

    AssetResolution third =
        ingest(context, "stream-mixed", 3, "evt-mixed-3",
            Map.of("resource_uid", "deploy-1", "dns", "shared.demo"));

    assertEquals(
        assertInstanceOf(Resolved.class, first).assetId(),
        assertInstanceOf(Resolved.class, third).assetId());
    assertEquals(2, count("assets", "t-mixed"));
    assertEquals("RESOLVED", observation("t-mixed", "evt-mixed-3").outcome());
  }

  @Test
  void failureAfterWritesRollsBackEverythingAndRetrySucceedsOnce() {
    TrustedContext context = new TrustedContext("t-fail", "eng-1");
    CanonicalSourceEvent canonical =
        observationEvent("evt-fail-1", "stream-fail", 1,
            Map.of("resource_uid", "deploy-1", "pod_uid", "pod-a"));

    assertThrows(
        IllegalStateException.class,
        () ->
            transaction.ingest(
                context,
                canonical,
                handle -> {
                  service.recordAssetObservation(
                      handle, context, "evt-fail-1", observationPayload(canonical));
                  throw new IllegalStateException("forced failure after identity writes");
                }));
    assertEquals(0, count("source_events", "t-fail"));
    assertEquals(0, count("assets", "t-fail"));
    assertEquals(0, count("asset_observations", "t-fail"));

    AssetResolution retried = ingestCanonical(context, canonical);
    assertInstanceOf(Resolved.class, retried);
    assertEquals(1, count("source_events", "t-fail"));
    assertEquals(1, count("assets", "t-fail"));
    assertEquals(1, count("asset_observations", "t-fail"));
  }

  @Test
  void exactSourceRetryAppendsNoObservation() {
    TrustedContext context = new TrustedContext("t-retry", "eng-1");
    CanonicalSourceEvent canonical =
        observationEvent("evt-retry-1", "stream-id-retry", 1,
            Map.of("resource_uid", "deploy-1", "pod_uid", "pod-a"));
    ingestCanonical(context, canonical);

    IngestResult retry =
        transaction.ingest(
            context, canonical, handle -> fail("work must not run on an exact retry"));

    assertEquals(IngestResult.NO_OP, retry);
    assertEquals(1, count("asset_observations", "t-retry"));
    assertEquals(1, count("assets", "t-retry"));
  }

  @Test
  void sequentiallyShuffledAuthoritativeInputsConvergeOnOneAsset() {
    TrustedContext context = new TrustedContext("t-shuffle", "eng-1");
    AssetResolution second =
        ingest(context, "stream-shuffle", 2, "evt-shuffle-2",
            Map.of("resource_uid", "deploy-1", "pod_uid", "pod-b"));
    AssetResolution third =
        ingest(context, "stream-shuffle", 3, "evt-shuffle-3",
            Map.of("resource_uid", "deploy-1", "pod_uid", "pod-c"));
    AssetResolution first =
        ingest(context, "stream-shuffle", 1, "evt-shuffle-1",
            Map.of("resource_uid", "deploy-1", "pod_uid", "pod-a"));

    AssetId assetId = assertInstanceOf(Resolved.class, first).assetId();
    assertEquals(assetId, assertInstanceOf(Resolved.class, second).assetId());
    assertEquals(assetId, assertInstanceOf(Resolved.class, third).assetId());
    assertEquals(1, count("assets", "t-shuffle"));
    assertEquals(3, count("asset_observations", "t-shuffle"));
  }

  private static AssetResolution ingest(
      TrustedContext context,
      String streamId,
      long sequence,
      String eventId,
      Map<String, Object> payloadFields) {
    return ingestCanonical(context, observationEvent(eventId, streamId, sequence, payloadFields));
  }

  private static AssetResolution ingestCanonical(
      TrustedContext context, CanonicalSourceEvent canonical) {
    AtomicReference<AssetResolution> resolution = new AtomicReference<>();
    transaction.ingest(
        context,
        canonical,
        handle ->
            resolution.set(
                service.recordAssetObservation(
                    handle,
                    context,
                    canonical.event().sourceEventId(),
                    observationPayload(canonical))));
    return resolution.get();
  }

  private static AssetObservation observationPayload(CanonicalSourceEvent canonical) {
    return (AssetObservation) canonical.event().payload();
  }

  private static CanonicalSourceEvent observationEvent(
      String eventId, String streamId, long sequence, Map<String, Object> payloadFields) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cluster_id", "cluster-demo");
    payload.put("resource_kind", "Deployment");
    payload.putAll(payloadFields);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("source_event_id", eventId);
    envelope.put("event_type", "asset_observation");
    envelope.put("source_stream_id", streamId);
    envelope.put("source_sequence", sequence);
    envelope.put("schema_version", 1);
    envelope.put("occurred_at", OCCURRED_AT);
    envelope.put("payload", payload);
    try {
      return codec.decode(MAPPER.writeValueAsString(envelope));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("test envelope failed to serialize", e);
    }
  }

  private static int count(String table, String tenantId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery("SELECT count(*) FROM " + table + " WHERE tenant_id = :tenantId")
                .bind("tenantId", tenantId)
                .mapTo(Integer.class)
                .one());
  }

  private record ObservationRow(
      String outcome, String basis, UUID assetId, String podUid, String dns, String banner,
      String labelsType) {}

  private static ObservationRow observation(String tenantId, String sourceEventId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT resolution_outcome, resolution_basis, canonical_asset_id, pod_uid,"
                        + " dns, banner, jsonb_typeof(labels) AS labels_type"
                        + " FROM asset_observations WHERE tenant_id = :tenantId"
                        + " AND source_event_id = :sourceEventId")
                .bind("tenantId", tenantId)
                .bind("sourceEventId", sourceEventId)
                .map(
                    (rs, ctx) ->
                        new ObservationRow(
                            rs.getString("resolution_outcome"),
                            rs.getString("resolution_basis"),
                            rs.getObject("canonical_asset_id", UUID.class),
                            rs.getString("pod_uid"),
                            rs.getString("dns"),
                            rs.getString("banner"),
                            rs.getString("labels_type")))
                .one());
  }
}
