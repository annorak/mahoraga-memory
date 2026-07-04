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

  void assertAssetIdentity() throws SQLException {
    asset("t-asset", UUID.randomUUID(), "cluster-demo", "deploy-1");
    assertViolation(
        "uq_assets_authoritative_key",
        "INSERT INTO assets (tenant_id, canonical_asset_id, cluster_id, resource_kind,"
            + " resource_uid) VALUES ('t-asset', ?, 'cluster-demo', 'Deployment', 'deploy-1')",
        UUID.randomUUID());
    assertViolation(
        "ck_assets_resource_kind",
        "INSERT INTO assets (tenant_id, canonical_asset_id, cluster_id, resource_kind,"
            + " resource_uid) VALUES ('t-asset', ?, 'cluster-demo', 'Pod', 'pod-1')",
        UUID.randomUUID());
    asset("t-asset-b", UUID.randomUUID(), "cluster-demo", "deploy-1");
  }

  void assertObservationSignalsAreMeaningful() throws SQLException {
    engagement("t-signal", "eng-1", "stream-signal");
    assertBlankEvidenceSignals();
    assertEmptyLabelsDoNotCountAsASignal();
  }

  private void assertBlankEvidenceSignals() throws SQLException {
    String[] whitespaceOnly = {"   ", "\t", "\n", "\r\n"};
    String[][] fields = {
      {"pod_uid", "dns", "api.demo", "ck_asset_observations_pod_uid_nonblank"},
      {"pod_name", "dns", "api.demo", "ck_asset_observations_pod_name_nonblank"},
      {"ip_address", "dns", "api.demo", "ck_asset_observations_ip_address_nonblank"},
      {"dns", "pod_uid", "pod-1", "ck_asset_observations_dns_nonblank"},
      {"banner", "dns", "api.demo", "ck_asset_observations_banner_nonblank"}
    };
    long sequence = 1;
    for (String[] field : fields) {
      String eventId = "evt-signal-" + sequence;
      sourceEvent(
          "t-signal", "eng-1", "stream-signal", sequence++, eventId, "asset_observation");
      String sql =
          "INSERT INTO asset_observations"
              + " (tenant_id, source_event_id, cluster_id, resource_kind, "
              + field[0]
              + ", "
              + field[1]
              + ", resolution_outcome, resolution_policy_version, resolution_basis)"
              + " VALUES ('t-signal', ?, 'cluster-demo', 'Deployment', ?, ?,"
              + " 'AMBIGUOUS', 1, 'WEAK_SIGNAL_COLLISION')";
      for (String blank : whitespaceOnly) {
        assertViolation(field[3], sql, eventId, blank, field[2]);
      }
    }
  }

  private void assertEmptyLabelsDoNotCountAsASignal() throws SQLException {
    sourceEvent(
        "t-signal", "eng-1", "stream-signal", 6, "evt-empty-labels", "asset_observation");
    assertViolation(
        "ck_asset_observations_signal_present",
        "INSERT INTO asset_observations"
            + " (tenant_id, source_event_id, cluster_id, resource_kind, labels,"
            + " resolution_outcome, resolution_policy_version, resolution_basis)"
            + " VALUES ('t-signal', 'evt-empty-labels', 'cluster-demo', 'Deployment',"
            + " '{}'::jsonb, 'AMBIGUOUS', 1, 'WEAK_SIGNAL_COLLISION')");

    sourceEvent(
        "t-signal",
        "eng-1",
        "stream-signal",
        7,
        "evt-empty-labels-with-dns",
        "asset_observation");
    exec(
        "INSERT INTO asset_observations"
            + " (tenant_id, source_event_id, cluster_id, resource_kind, dns, labels,"
            + " resolution_outcome, resolution_policy_version, resolution_basis)"
            + " VALUES ('t-signal', 'evt-empty-labels-with-dns', 'cluster-demo',"
            + " 'Deployment', 'api.demo', '{}'::jsonb,"
            + " 'AMBIGUOUS', 1, 'WEAK_SIGNAL_COLLISION')");
  }

  void assertEveryCrossTenantReference() throws SQLException {
    UUID assetA = seedTenant("t-iso-a", "evt-iso-a");
    UUID assetB = seedTenant("t-iso-b", "evt-iso-b");
    UUID findingA = UUID.randomUUID();
    UUID findingB = UUID.randomUUID();
    finding("t-iso-a", findingA, assetA, "xss", "sig-a");
    finding("t-iso-b", findingB, assetB, "xss", "sig-b");
    assertCrossTenantSourceBinding();
    assertCrossTenantAssetReferences(assetA, assetB);
    assertCrossTenantFindingReferences(assetA, findingA, findingB);
  }

  private void assertCrossTenantSourceBinding() {
    assertViolation(
        "fk_source_events_engagement",
        sourceEventSql(
            "t-iso-b",
            "eng-1",
            "stream-t-iso-a",
            2,
            "evt-cross-engagement",
            "asset_observation"));
  }

  private void assertCrossTenantAssetReferences(UUID assetA, UUID assetB) {
    assertViolation(
        "fk_test_attempts_source_event",
        attemptSql("t-iso-b", "evt-iso-a", assetB, "completed", "'detected'"));
    assertViolation(
        "fk_test_attempts_asset",
        attemptSql("t-iso-b", "evt-iso-b", assetA, "completed", "'detected'"));
    assertViolation(
        "fk_asset_observations_source_event",
        observationSql("RESOLVED", "AUTHORITATIVE_DEPLOYMENT_KEY"),
        "t-iso-b",
        "evt-iso-a",
        assetB,
        "deploy-uid-t-iso-b");
    assertViolation(
        "fk_asset_observations_asset",
        observationSql("RESOLVED", "AUTHORITATIVE_DEPLOYMENT_KEY"),
        "t-iso-b",
        "evt-iso-b",
        assetA,
        "deploy-uid-t-iso-a");
  }

  private void assertCrossTenantFindingReferences(
      UUID assetA, UUID findingA, UUID findingB) {
    assertViolation(
        "fk_findings_asset",
        "INSERT INTO findings (tenant_id, finding_id, canonical_asset_id, vuln_class,"
            + " normalized_location_signature, match_key_version, verification_key,"
            + " check_version, relevant_context_hash, compatibility_policy_version)"
            + " VALUES ('t-iso-b', ?, ?, 'xss', 'sig-x', 1, 'check-key', '1.0', ?, 1)",
        UUID.randomUUID(),
        assetA,
        VALID_HASH);
    assertViolation(
        "fk_finding_occurrences_source_event",
        "INSERT INTO finding_occurrences (tenant_id, source_event_id, finding_id)"
            + " VALUES ('t-iso-b', 'evt-iso-a', ?)",
        findingB);
    assertViolation(
        "fk_finding_occurrences_finding",
        "INSERT INTO finding_occurrences (tenant_id, source_event_id, finding_id)"
            + " VALUES ('t-iso-b', 'evt-iso-b', ?)",
        findingA);
  }

  int assertAttemptPairs(
      UUID assetId, String[][] pairs, int sequence, boolean isLegal) throws SQLException {
    for (String[] pair : pairs) {
      String eventId = "evt-att-" + sequence;
      sourceEvent("t-att", "eng-1", "stream-t-att", ++sequence, eventId, "test_attempt");
      String sql = attemptSql("t-att", eventId, assetId, pair[0], pair[1]);
      if (isLegal) {
        exec(sql);
      } else {
        assertViolation("ck_test_attempts_outcome_pairing", sql);
      }
    }
    return sequence;
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
