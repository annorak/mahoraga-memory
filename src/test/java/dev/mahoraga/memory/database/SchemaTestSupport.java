package dev.mahoraga.memory.database;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Shared JDBC helpers and seed rows for the schema constraint tests. */
final class SchemaTestSupport {

  static final String VALID_HASH = "a".repeat(64);
  private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.parse("2026-01-01T10:00:00Z");

  private final Connection connection;

  SchemaTestSupport(Connection connection) {
    this.connection = connection;
  }

  void exec(String sql, Object... params) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    }
  }

  void assertViolation(String constraint, String sql, Object... params) {
    SQLException error = assertThrows(SQLException.class, () -> exec(sql, params), constraint);
    assertTrue(
        error.getMessage().contains(constraint),
        "expected violation of " + constraint + " but got: " + error.getMessage());
  }

  void engagement(String tenant, String engagement, String stream) throws SQLException {
    exec(
        "INSERT INTO engagements (tenant_id, engagement_id, source_stream_id) VALUES (?, ?, ?)",
        tenant,
        engagement,
        stream);
  }

  void sourceEvent(
      String tenant, String engagement, String stream, long sequence, String eventId, String type)
      throws SQLException {
    exec(
        """
        INSERT INTO source_events (tenant_id, engagement_id, source_event_id, event_type,
          source_stream_id, source_sequence, schema_version, occurred_at, payload,
          canonical_source_hash)
        VALUES (?, ?, ?, ?, ?, ?, 1, ?, '{"k":1}'::jsonb, ?)
        """,
        tenant,
        engagement,
        eventId,
        type,
        stream,
        sequence,
        OCCURRED_AT,
        VALID_HASH);
  }

  void asset(String tenant, UUID assetId, String cluster, String resourceUid) throws SQLException {
    exec(
        "INSERT INTO assets (tenant_id, canonical_asset_id, cluster_id, resource_kind,"
            + " resource_uid) VALUES (?, ?, ?, 'Deployment', ?)",
        tenant,
        assetId,
        cluster,
        resourceUid);
  }

  void finding(String tenant, UUID findingId, UUID assetId, String vulnClass, String location)
      throws SQLException {
    exec(
        """
        INSERT INTO findings (tenant_id, finding_id, canonical_asset_id, vuln_class,
          normalized_location_signature, match_key_version, verification_key, check_version,
          relevant_context_hash, compatibility_policy_version)
        VALUES (?, ?, ?, ?, ?, 1, 'check-key', '1.0', ?, 1)
        """,
        tenant,
        findingId,
        assetId,
        vulnClass,
        location,
        VALID_HASH);
  }

  /** Seeds one engagement, one source event, and one asset under the tenant. */
  UUID seedTenant(String tenant, String eventId) throws SQLException {
    engagement(tenant, "eng-1", "stream-" + tenant);
    sourceEvent(tenant, "eng-1", "stream-" + tenant, 1, eventId, "asset_observation");
    UUID assetId = UUID.randomUUID();
    asset(tenant, assetId, "cluster-demo", "deploy-uid-" + tenant);
    return assetId;
  }

  static String sourceEventSql(
      String tenant, String engagement, String stream, long sequence, String eventId, String type) {
    return sourceEventSql(tenant, engagement, stream, sequence, eventId, type, 1, VALID_HASH,
        "{\"k\":1}");
  }

  static String sourceEventSql(
      String tenant, String engagement, String stream, long sequence, String eventId, String type,
      int schemaVersion, String hash, String payloadJson) {
    return ("INSERT INTO source_events (tenant_id, engagement_id, source_event_id, event_type,"
            + " source_stream_id, source_sequence, schema_version, occurred_at, payload,"
            + " canonical_source_hash) VALUES ('%s', '%s', '%s', '%s', '%s', %d, %d,"
            + " timestamptz '2026-01-01T10:00:00Z', '%s'::jsonb, '%s')")
        .formatted(
            tenant, engagement, eventId, type, stream, sequence, schemaVersion, payloadJson, hash);
  }

  static String observationSql(String outcome, String basis) {
    return ("INSERT INTO asset_observations (tenant_id, source_event_id, canonical_asset_id,"
            + " cluster_id, resource_kind, resource_uid, pod_uid, resolution_outcome,"
            + " resolution_policy_version, resolution_basis) VALUES (?, ?, ?, 'cluster-demo',"
            + " 'Deployment', ?, 'pod-1', '%s', 1, '%s')")
        .formatted(outcome, basis);
  }

  static String attemptSql(
      String tenant, String eventId, UUID assetId, String status, String resultLiteral) {
    return ("INSERT INTO test_attempts (tenant_id, source_event_id, canonical_asset_id,"
            + " verification_key, check_version, relevant_context_hash,"
            + " compatibility_policy_version, execution_status, result)"
            + " VALUES ('%s', '%s', '%s', 'check-key', '1.0', '%s', 1, '%s', %s)")
        .formatted(tenant, eventId, assetId, VALID_HASH, status, resultLiteral);
  }
}
