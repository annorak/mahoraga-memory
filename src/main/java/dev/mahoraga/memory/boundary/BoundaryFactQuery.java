package dev.mahoraga.memory.boundary;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.posture.SelectedFact;
import jakarta.inject.Inject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.Query;

/**
 * Selects exactly the facts visible at a knowledge boundary: same trusted
 * tenant, fact's stream named by the boundary, and source position at or below
 * that stream's finalized limit. Position filtering happens here, before any
 * domain chronology; a backdated event outside the selected positions stays
 * invisible, and stream ids never widen tenant access. The boundary binds as
 * two parallel arrays joined through {@code unnest}, so any number of streams
 * uses one set-based statement per fact table and never one query per stream.
 * Rows return in stable stream/sequence order for reproducibility only; domain
 * ordering is the fold's contract, not this query's.
 */
public final class BoundaryFactQuery {

  private static final String OCCURRENCE_SQL =
      """
      SELECT se.engagement_id, se.source_event_id, se.source_stream_id, se.source_sequence,
        se.occurred_at, fo.finding_id, f.canonical_asset_id, f.vuln_class,
        f.normalized_location_signature, f.match_key_version, f.verification_key,
        f.check_version, f.relevant_context_hash, f.compatibility_policy_version
      FROM unnest(:streamIds, :limits) AS boundary(stream_id, position_limit)
      JOIN source_events se ON se.tenant_id = :tenantId
        AND se.source_stream_id = boundary.stream_id
        AND se.source_sequence <= boundary.position_limit
      JOIN finding_occurrences fo ON fo.tenant_id = se.tenant_id
        AND fo.source_event_id = se.source_event_id
      JOIN findings f ON f.tenant_id = fo.tenant_id AND f.finding_id = fo.finding_id
      ORDER BY se.source_stream_id, se.source_sequence
      """;

  private static final String ATTEMPT_SQL =
      """
      SELECT se.engagement_id, se.source_event_id, se.source_stream_id, se.source_sequence,
        se.occurred_at, ta.canonical_asset_id, ta.verification_key, ta.check_version,
        ta.relevant_context_hash, ta.compatibility_policy_version, ta.execution_status,
        ta.result
      FROM unnest(:streamIds, :limits) AS boundary(stream_id, position_limit)
      JOIN source_events se ON se.tenant_id = :tenantId
        AND se.source_stream_id = boundary.stream_id
        AND se.source_sequence <= boundary.position_limit
      JOIN test_attempts ta ON ta.tenant_id = se.tenant_id
        AND ta.source_event_id = se.source_event_id
      ORDER BY se.source_stream_id, se.source_sequence
      """;

  @Inject
  public BoundaryFactQuery() {}

  /**
   * All finding occurrences followed by all test attempts visible at the
   * boundary for the trusted tenant. Standalone attempts whose finding has no
   * in-bound occurrence are preserved; nothing is synthesized for them.
   */
  public List<SelectedFact> selectFacts(
      Handle handle, TrustedContext context, KnowledgeBoundary boundary) {
    List<SelectedFact> facts = new ArrayList<>();
    facts.addAll(
        boundBoundary(handle, OCCURRENCE_SQL, context, boundary)
            .map((rs, ctx) -> mapOccurrence(rs, context))
            .list());
    facts.addAll(
        boundBoundary(handle, ATTEMPT_SQL, context, boundary)
            .map((rs, ctx) -> mapAttempt(rs, context))
            .list());
    return List.copyOf(facts);
  }

  private Query boundBoundary(
      Handle handle, String sql, TrustedContext context, KnowledgeBoundary boundary) {
    List<String> streamIds =
        boundary.positions().stream().map(BoundaryPosition::sourceStreamId).toList();
    List<Long> limits =
        boundary.positions().stream().map(BoundaryPosition::lastDataSequence).toList();
    return handle
        .createQuery(sql)
        .bind("tenantId", context.tenantId())
        .bindArray("streamIds", String.class, streamIds)
        .bindArray("limits", Long.class, limits);
  }

  private static SelectedFact mapOccurrence(ResultSet rs, TrustedContext context)
      throws SQLException {
    return new SelectedFact.FindingOccurrence(
        context.tenantId(),
        rs.getString("engagement_id"),
        rs.getString("source_event_id"),
        rs.getString("source_stream_id"),
        rs.getLong("source_sequence"),
        rs.getTimestamp("occurred_at").toInstant(),
        rs.getObject("finding_id", UUID.class),
        rs.getObject("canonical_asset_id", UUID.class),
        rs.getString("vuln_class"),
        rs.getString("normalized_location_signature"),
        rs.getInt("match_key_version"),
        rs.getString("verification_key"),
        rs.getString("check_version"),
        rs.getString("relevant_context_hash"),
        rs.getInt("compatibility_policy_version"));
  }

  private static SelectedFact mapAttempt(ResultSet rs, TrustedContext context)
      throws SQLException {
    String result = rs.getString("result");
    return new SelectedFact.TestAttempt(
        context.tenantId(),
        rs.getString("engagement_id"),
        rs.getString("source_event_id"),
        rs.getString("source_stream_id"),
        rs.getLong("source_sequence"),
        rs.getTimestamp("occurred_at").toInstant(),
        rs.getObject("canonical_asset_id", UUID.class),
        rs.getString("verification_key"),
        rs.getString("check_version"),
        rs.getString("relevant_context_hash"),
        rs.getInt("compatibility_policy_version"),
        ExecutionStatus.fromWire(rs.getString("execution_status")),
        result == null ? null : TestResult.fromWire(result));
  }
}
