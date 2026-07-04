package dev.mahoraga.memory.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact PostgreSQL catalog contract for the seven-table schema. */
final class SchemaCatalog {

  private static final Set<String> EXPECTED_TABLES =
      Set.of(
          "engagements",
          "source_events",
          "assets",
          "asset_observations",
          "findings",
          "finding_occurrences",
          "test_attempts");

  private static final Set<String> EXPECTED_CONSTRAINTS =
      Set.of(
          """
          pk_engagements uq_engagements_source_stream uq_engagements_stream_binding
          ck_engagements_tenant_nonblank ck_engagements_engagement_nonblank
          ck_engagements_stream_nonblank ck_engagements_last_sequence_positive
          pk_source_events uq_source_events_stream_position fk_source_events_engagement
          ck_source_events_tenant_nonblank ck_source_events_engagement_nonblank
          ck_source_events_event_id_nonblank ck_source_events_stream_nonblank
          ck_source_events_event_type ck_source_events_sequence_positive
          ck_source_events_schema_version ck_source_events_payload_object
          ck_source_events_hash_hex
          pk_assets uq_assets_authoritative_key uq_assets_exact_reference
          ck_assets_tenant_nonblank ck_assets_cluster_nonblank
          ck_assets_resource_uid_nonblank ck_assets_resource_kind
          pk_asset_observations fk_asset_observations_source_event
          fk_asset_observations_asset ck_asset_observations_tenant_nonblank
          ck_asset_observations_cluster_nonblank
          ck_asset_observations_resource_uid_nonblank
          ck_asset_observations_pod_uid_nonblank
          ck_asset_observations_pod_name_nonblank
          ck_asset_observations_ip_address_nonblank
          ck_asset_observations_dns_nonblank
          ck_asset_observations_banner_nonblank
          ck_asset_observations_resource_kind
          ck_asset_observations_policy_version
          ck_asset_observations_labels_object
          ck_asset_observations_signal_present
          ck_asset_observations_resolution_pairing
          pk_findings uq_findings_match_identity fk_findings_asset
          ck_findings_tenant_nonblank ck_findings_vuln_class_nonblank
          ck_findings_location_nonblank ck_findings_verification_key_nonblank
          ck_findings_check_version_nonblank ck_findings_match_key_version
          ck_findings_policy_version ck_findings_context_hash_hex
          pk_finding_occurrences fk_finding_occurrences_source_event
          fk_finding_occurrences_finding ck_finding_occurrences_tenant_nonblank
          pk_test_attempts fk_test_attempts_source_event fk_test_attempts_asset
          ck_test_attempts_tenant_nonblank
          ck_test_attempts_verification_key_nonblank
          ck_test_attempts_check_version_nonblank
          ck_test_attempts_policy_version
          ck_test_attempts_context_hash_hex
          ck_test_attempts_execution_status
          ck_test_attempts_result
          ck_test_attempts_outcome_pairing
          """
              .strip()
              .split("\\s+"));

  private static final Map<String, List<String>> EXPECTED_FOREIGN_KEYS =
      Map.of(
          "fk_source_events_engagement",
              List.of("tenant_id", "engagement_id", "source_stream_id"),
          "fk_asset_observations_source_event",
              List.of("tenant_id", "source_event_id"),
          "fk_asset_observations_asset",
              List.of(
                  "tenant_id",
                  "canonical_asset_id",
                  "cluster_id",
                  "resource_kind",
                  "resource_uid"),
          "fk_findings_asset",
              List.of("tenant_id", "canonical_asset_id"),
          "fk_finding_occurrences_source_event",
              List.of("tenant_id", "source_event_id"),
          "fk_finding_occurrences_finding",
              List.of("tenant_id", "finding_id"),
          "fk_test_attempts_source_event",
              List.of("tenant_id", "source_event_id"),
          "fk_test_attempts_asset",
              List.of("tenant_id", "canonical_asset_id"));

  private static final Map<String, IndexContract> EXPECTED_INDEXES =
      Map.of(
          "uq_source_events_completion_marker",
              new IndexContract(
                  "source_events", true, List.of("tenant_id", "source_stream_id"), true),
          "ix_finding_occurrences_finding",
              new IndexContract(
                  "finding_occurrences", false, List.of("tenant_id", "finding_id"), false),
          "ix_test_attempts_coverage",
              new IndexContract(
                  "test_attempts",
                  false,
                  List.of(
                      "tenant_id",
                      "canonical_asset_id",
                      "verification_key",
                      "check_version",
                      "relevant_context_hash",
                      "compatibility_policy_version"),
                  false));

  private SchemaCatalog() {}

  static void assertExactSchema(Connection connection) throws SQLException {
    assertEquals(EXPECTED_TABLES, queryNames(connection, TABLE_QUERY));
    assertEquals(EXPECTED_CONSTRAINTS, queryNames(connection, CONSTRAINT_QUERY));
    assertEquals(EXPECTED_FOREIGN_KEYS, queryForeignKeys(connection));
    assertEquals(EXPECTED_INDEXES, queryIndexes(connection));
  }

  private static Set<String> queryNames(Connection connection, String sql) throws SQLException {
    Set<String> names = new HashSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        names.add(rows.getString(1));
      }
    }
    return names;
  }

  private static Map<String, List<String>> queryForeignKeys(Connection connection)
      throws SQLException {
    Map<String, List<String>> foreignKeys = new HashMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(FOREIGN_KEY_QUERY)) {
      while (rows.next()) {
        foreignKeys.put(rows.getString(1), List.of(rows.getString(2).split(",")));
      }
    }
    return foreignKeys;
  }

  private static Map<String, IndexContract> queryIndexes(Connection connection)
      throws SQLException {
    Map<String, IndexContract> indexes = new HashMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(INDEX_QUERY)) {
      while (rows.next()) {
        indexes.put(
            rows.getString(1),
            new IndexContract(
                rows.getString(2),
                rows.getBoolean(3),
                List.of(rows.getString(4).split(",")),
                rows.getBoolean(5)));
      }
    }
    return indexes;
  }

  private static final String TABLE_QUERY =
      """
      SELECT table_name
      FROM information_schema.tables
      WHERE table_schema = 'public'
        AND table_type = 'BASE TABLE'
        AND table_name <> 'flyway_schema_history'
      """;

  private static final String CONSTRAINT_QUERY =
      """
      SELECT c.conname
      FROM pg_constraint c
      JOIN pg_namespace n ON n.oid = c.connamespace
      WHERE n.nspname = 'public'
        AND c.contype IN ('p', 'u', 'f', 'c')
      """;

  private static final String FOREIGN_KEY_QUERY =
      """
      SELECT c.conname, string_agg(a.attname, ',' ORDER BY k.ordinality)
      FROM pg_constraint c
      JOIN pg_class r ON r.oid = c.conrelid
      JOIN pg_namespace n ON n.oid = r.relnamespace
      CROSS JOIN LATERAL unnest(c.conkey)
        WITH ORDINALITY AS k(attnum, ordinality)
      JOIN pg_attribute a
        ON a.attrelid = c.conrelid AND a.attnum = k.attnum
      WHERE n.nspname = 'public' AND c.contype = 'f'
      GROUP BY c.conname
      """;

  private static final String INDEX_QUERY =
      """
      SELECT index_relation.relname,
             table_relation.relname,
             index_data.indisunique,
             string_agg(
                 COALESCE(attribute.attname::text, '<expression>'),
                 ',' ORDER BY indexed_column.ordinality),
             index_data.indpred IS NOT NULL
      FROM pg_index index_data
      JOIN pg_class index_relation
        ON index_relation.oid = index_data.indexrelid
      JOIN pg_class table_relation
        ON table_relation.oid = index_data.indrelid
      JOIN pg_namespace namespace
        ON namespace.oid = table_relation.relnamespace
      CROSS JOIN LATERAL unnest(index_data.indkey::smallint[])
        WITH ORDINALITY AS indexed_column(attnum, ordinality)
      LEFT JOIN pg_attribute attribute
        ON attribute.attrelid = table_relation.oid
        AND attribute.attnum = indexed_column.attnum
      WHERE namespace.nspname = 'public'
        AND table_relation.relname <> 'flyway_schema_history'
        AND indexed_column.ordinality <= index_data.indnkeyatts
        AND index_data.indisvalid
        AND index_data.indisready
        AND index_data.indislive
        AND NOT EXISTS (
            SELECT 1
            FROM pg_constraint constraint_data
            WHERE constraint_data.conindid = index_data.indexrelid)
      GROUP BY index_relation.relname,
               table_relation.relname,
               index_data.indisunique,
               (index_data.indpred IS NOT NULL)
      """;

  private record IndexContract(
      String tableName, boolean isUnique, List<String> columns, boolean isPartial) {}
}
