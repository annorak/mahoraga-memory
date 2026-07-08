package dev.mahoraga.memory.database;

import static dev.mahoraga.memory.database.SchemaTestSupport.VALID_HASH;
import static dev.mahoraga.memory.database.SchemaTestSupport.attemptSql;
import static dev.mahoraga.memory.database.SchemaTestSupport.observationSql;
import static dev.mahoraga.memory.database.SchemaTestSupport.sourceEventSql;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Covers the V1 schema invariants against real PostgreSQL. Requires Docker and
 * fails rather than skipping when Docker is unavailable.
 */
@Testcontainers
class PostgreSqlSchemaTest {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.4-alpine");

  private static Connection connection;
  private static SchemaTestSupport db;
  private static int firstRunMigrations;

  @BeforeAll
  static void migrateAndConnect() throws SQLException {
    firstRunMigrations = flyway().migrate().migrationsExecuted;
    connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    db = new SchemaTestSupport(connection);
  }

  @AfterAll
  static void closeConnection() throws SQLException {
    if (connection != null) {
      connection.close();
    }
  }

  private static Flyway flyway() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load();
  }

  @Test
  void migratesOnceThenRerunsAsValidatedNoOp() {
    assertEquals(1, firstRunMigrations);
    assertEquals(0, flyway().migrate().migrationsExecuted);
    assertDoesNotThrow(() -> flyway().validate());
  }

  @Test
  void createsExactSchemaContract() throws SQLException {
    SchemaCatalog.assertExactSchema(connection);
  }

  @Test
  void streamCannotBeReboundAcrossTenantOrEngagement() throws SQLException {
    db.engagement("t-stream", "eng-1", "stream-bind");
    db.assertViolation(
        "uq_engagements_source_stream",
        "INSERT INTO engagements (tenant_id, engagement_id, source_stream_id)"
            + " VALUES ('t-stream-other', 'eng-9', 'stream-bind')");
    db.assertViolation(
        "uq_engagements_source_stream",
        "INSERT INTO engagements (tenant_id, engagement_id, source_stream_id)"
            + " VALUES ('t-stream', 'eng-2', 'stream-bind')");
  }

  @Test
  void enforcesSourceEventIdentityAndContent() throws SQLException {
    db.engagement("t-se", "eng-1", "stream-se");
    db.engagement("t-se", "eng-2", "stream-se2");
    db.sourceEvent("t-se", "eng-1", "stream-se", 1, "evt-1", "asset_observation");

    db.assertViolation(
        "pk_source_events",
        sourceEventSql("t-se", "eng-1", "stream-se", 2, "evt-1", "asset_observation"));
    db.assertViolation(
        "uq_source_events_stream_position",
        sourceEventSql("t-se", "eng-1", "stream-se", 1, "evt-2", "asset_observation"));
    // Engagement/stream binding mismatch: eng-2 owns stream-se2, not stream-se.
    db.assertViolation(
        "fk_source_events_engagement",
        sourceEventSql("t-se", "eng-2", "stream-se", 3, "evt-3", "asset_observation"));
    db.assertViolation(
        "ck_source_events_event_type",
        sourceEventSql("t-se", "eng-1", "stream-se", 4, "evt-4", "mystery"));
    db.assertViolation(
        "ck_source_events_sequence_positive",
        sourceEventSql("t-se", "eng-1", "stream-se", 0, "evt-5", "asset_observation"));
    db.assertViolation(
        "ck_source_events_schema_version",
        sourceEventSql("t-se", "eng-1", "stream-se", 5, "evt-6", "asset_observation", 2,
            VALID_HASH, "{\"k\":1}"));
    db.assertViolation(
        "ck_source_events_hash_hex",
        sourceEventSql("t-se", "eng-1", "stream-se", 6, "evt-7", "asset_observation", 1,
            "Z".repeat(64), "{\"k\":1}"));
    db.assertViolation(
        "ck_source_events_payload_object",
        sourceEventSql("t-se", "eng-1", "stream-se", 7, "evt-8", "asset_observation", 1,
            VALID_HASH, "[1,2]"));

    // Same event ID under another tenant is a distinct, legal identity.
    db.engagement("t-se-b", "eng-1", "stream-se-b");
    db.sourceEvent("t-se-b", "eng-1", "stream-se-b", 1, "evt-1", "asset_observation");
  }

  @Test
  void allowsAtMostOneCompletionMarkerPerStream() throws SQLException {
    db.engagement("t-cm", "eng-1", "stream-cm");
    db.sourceEvent("t-cm", "eng-1", "stream-cm", 4, "evt-cm-1", "engagement_completed");
    db.assertViolation(
        "uq_source_events_completion_marker",
        sourceEventSql("t-cm", "eng-1", "stream-cm", 9, "evt-cm-2", "engagement_completed"));
  }

  @Test
  void roundTripsMicrosecondTimestampsExactly() throws SQLException {
    OffsetDateTime occurred = OffsetDateTime.parse("2026-01-01T10:00:00.123456Z");
    db.engagement("t-time", "eng-1", "stream-time");
    db.exec(
        """
        INSERT INTO source_events (tenant_id, engagement_id, source_event_id, event_type,
          source_stream_id, source_sequence, schema_version, occurred_at, payload,
          canonical_source_hash)
        VALUES ('t-time', 'eng-1', 'evt-time', 'asset_observation', 'stream-time', 1, 1, ?,
          '{}'::jsonb, ?)
        """,
        occurred,
        SchemaTestSupport.VALID_HASH);
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT occurred_at FROM source_events"
                    + " WHERE tenant_id = 't-time' AND source_event_id = 'evt-time'")) {
      rs.next();
      assertEquals(occurred.toInstant(), rs.getObject(1, OffsetDateTime.class).toInstant());
    }
  }

  @Test
  void enforcesAssetIdentity() throws SQLException {
    db.assertAssetIdentity();
  }

  @Test
  void requiresMeaningfulObservationSignals() throws SQLException {
    db.assertObservationSignalsAreMeaningful();
  }

  @Test
  void enforcesResolutionPairingsOnObservations() throws SQLException {
    UUID assetId = db.seedTenant("t-obs", "evt-obs-1");
    db.sourceEvent("t-obs", "eng-1", "stream-t-obs", 2, "evt-obs-2", "asset_observation");
    db.sourceEvent("t-obs", "eng-1", "stream-t-obs", 3, "evt-obs-3", "asset_observation");

    db.exec(observationSql("RESOLVED", "AUTHORITATIVE_DEPLOYMENT_KEY"),
        "t-obs", "evt-obs-1", assetId, "deploy-uid-t-obs");
    db.exec(
        "INSERT INTO asset_observations (tenant_id, source_event_id, cluster_id, resource_kind,"
            + " dns, resolution_outcome, resolution_policy_version, resolution_basis)"
            + " VALUES ('t-obs', 'evt-obs-2', 'cluster-demo', 'Deployment', 'api.demo',"
            + " 'AMBIGUOUS', 1, 'WEAK_SIGNAL_COLLISION')");

    db.assertViolation("ck_asset_observations_resolution_pairing",
        observationSql("RESOLVED", "WEAK_SIGNAL_COLLISION"),
        "t-obs", "evt-obs-3", assetId, "deploy-uid-t-obs");
    db.assertViolation("ck_asset_observations_resolution_pairing",
        observationSql("AMBIGUOUS", "WEAK_SIGNAL_COLLISION"),
        "t-obs", "evt-obs-3", assetId, "deploy-uid-t-obs");
    db.assertViolation("ck_asset_observations_resolution_pairing",
        observationSql("CREATED_PROVISIONAL", "AUTHORITATIVE_DEPLOYMENT_KEY"),
        "t-obs", "evt-obs-3", assetId, "deploy-uid-t-obs");
    // No observation signal at all.
    db.assertViolation("ck_asset_observations_signal_present",
        "INSERT INTO asset_observations (tenant_id, source_event_id, cluster_id, resource_kind,"
            + " resolution_outcome, resolution_policy_version, resolution_basis)"
            + " VALUES ('t-obs', 'evt-obs-3', 'cluster-demo', 'Deployment',"
            + " 'AMBIGUOUS', 1, 'WEAK_SIGNAL_COLLISION')");
    db.assertViolation("ck_asset_observations_labels_object",
        "INSERT INTO asset_observations (tenant_id, source_event_id, cluster_id, resource_kind,"
            + " labels, resolution_outcome, resolution_policy_version, resolution_basis)"
            + " VALUES ('t-obs', 'evt-obs-3', 'cluster-demo', 'Deployment', '[1]'::jsonb,"
            + " 'AMBIGUOUS', 1, 'WEAK_SIGNAL_COLLISION')");
    // Observation must reference an existing source event in the same tenant.
    db.assertViolation("fk_asset_observations_source_event",
        observationSql("RESOLVED", "AUTHORITATIVE_DEPLOYMENT_KEY"),
        "t-obs", "evt-missing", assetId, "deploy-uid-t-obs");
  }

  @Test
  void enforcesFindingIdentityAndOccurrenceLinks() throws SQLException {
    UUID assetId = db.seedTenant("t-find", "evt-find-1");
    UUID findingId = UUID.randomUUID();
    db.finding("t-find", findingId, assetId, "sql_injection", "sig-1");

    db.assertViolation(
        "uq_findings_match_identity",
        "INSERT INTO findings (tenant_id, finding_id, canonical_asset_id, vuln_class,"
            + " normalized_location_signature, match_key_version, verification_key,"
            + " check_version, relevant_context_hash, compatibility_policy_version)"
            + " VALUES ('t-find', ?, ?, 'sql_injection', 'sig-1', 1, 'check-key', '1.0', ?, 1)",
        UUID.randomUUID(), assetId, SchemaTestSupport.VALID_HASH);
    db.assertViolation(
        "ck_findings_context_hash_hex",
        "INSERT INTO findings (tenant_id, finding_id, canonical_asset_id, vuln_class,"
            + " normalized_location_signature, match_key_version, verification_key,"
            + " check_version, relevant_context_hash, compatibility_policy_version)"
            + " VALUES ('t-find', ?, ?, 'xss', 'sig-2', 1, 'check-key', '1.0', 'nope', 1)",
        UUID.randomUUID(), assetId);

    db.sourceEvent("t-find", "eng-1", "stream-t-find", 2, "evt-find-2", "finding_observation");
    db.exec(
        "INSERT INTO finding_occurrences (tenant_id, source_event_id, finding_id)"
            + " VALUES ('t-find', 'evt-find-1', ?)",
        findingId);
    db.assertViolation(
        "pk_finding_occurrences",
        "INSERT INTO finding_occurrences (tenant_id, source_event_id, finding_id)"
            + " VALUES ('t-find', 'evt-find-1', ?)",
        findingId);
    db.assertViolation(
        "fk_finding_occurrences_finding",
        "INSERT INTO finding_occurrences (tenant_id, source_event_id, finding_id)"
            + " VALUES ('t-find', 'evt-find-2', ?)",
        UUID.randomUUID());
  }

  @Test
  void blocksCrossTenantReferences() throws SQLException {
    db.assertEveryCrossTenantReference();
  }

  @Test
  void enforcesAttemptOutcomeLegality() throws SQLException {
    UUID assetId = db.seedTenant("t-att", "evt-att-0");
    String[][] legal = {
      {"completed", "'detected'"},
      {"completed", "'not_detected'"},
      {"failed", "NULL"},
      {"failed", "'inconclusive'"},
      {"blocked", "NULL"},
      {"blocked", "'inconclusive'"},
      {"partial", "NULL"},
      {"partial", "'inconclusive'"},
      {"skipped", "NULL"},
      {"skipped", "'inconclusive'"},
    };
    String[][] illegal = {
      {"completed", "NULL"},
      {"completed", "'inconclusive'"},
      {"failed", "'detected'"},
      {"failed", "'not_detected'"},
      {"blocked", "'detected'"},
      {"blocked", "'not_detected'"},
      {"partial", "'detected'"},
      {"partial", "'not_detected'"},
      {"skipped", "'detected'"},
      {"skipped", "'not_detected'"},
    };
    int sequence = 1;
    sequence = db.assertAttemptPairs(assetId, legal, sequence, true);
    db.assertAttemptPairs(assetId, illegal, sequence, false);
  }

  @Test
  void rejectsUnknownAttemptVocabulary() throws SQLException {
    UUID assetId = db.seedTenant("t-att-vocab", "evt-att-vocab-0");
    db.sourceEvent(
        "t-att-vocab", "eng-1", "stream-t-att-vocab", 2, "evt-status", "test_attempt");
    db.assertViolation(
        "ck_test_attempts_execution_status",
        attemptSql("t-att-vocab", "evt-status", assetId, "unknown", "NULL"));
    db.sourceEvent(
        "t-att-vocab", "eng-1", "stream-t-att-vocab", 3, "evt-result", "test_attempt");
    db.assertViolation(
        "ck_test_attempts",
        attemptSql("t-att-vocab", "evt-result", assetId, "completed", "'unknown'"));
  }

}
