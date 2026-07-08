package dev.mahoraga.memory.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mahoraga.memory.boundary.BoundaryPosition;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.demo.DemoArmEvidence.AmbiguityProof;
import dev.mahoraga.memory.demo.DemoArmEvidence.ConflictProbeResult;
import dev.mahoraga.memory.demo.DemoArmEvidence.MemoryReportSummary;
import dev.mahoraga.memory.demo.DemoArmEvidence.StableIdentityProof;
import dev.mahoraga.memory.demo.DemoArmEvidence.StatelessReportSummary;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.ingest.SourceEventConflictException;
import dev.mahoraga.memory.ingest.SourceEventIngestor;
import dev.mahoraga.memory.planning.SteeringArmEvidence;
import dev.mahoraga.memory.posture.PostureResult.EpisodeClassification;
import dev.mahoraga.memory.reporting.Report;
import dev.mahoraga.memory.reporting.Report.MemorySummary;
import dev.mahoraga.memory.reporting.Report.StatelessSummary;
import dev.mahoraga.memory.reporting.ReportRenderer;
import dev.mahoraga.memory.reporting.ReportService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.jdbi.v3.core.Jdbi;

/** Derives demo-only proof values from persisted state and production services. */
final class DemoProofCollector {

  private static final long REPLAY_SHUFFLE_SEED = 42L;

  private final Jdbi jdbi;
  private final SourceEventIngestor ingestor;
  private final ObjectMapper objectMapper;
  private final SourceEventCodec codec;
  private final ReportService reportService = new ReportService();

  DemoProofCollector(
      Jdbi jdbi,
      SourceEventIngestor ingestor,
      ObjectMapper objectMapper,
      SourceEventCodec codec) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    this.ingestor = Objects.requireNonNull(ingestor, "ingestor");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  DemoExecutionProof collect(DemoFixtures fixtures, SteeringArmEvidence steering) {
    ReportPair reports = reports(fixtures);
    requireArmReports(steering, reports);
    StableIdentityProof identity = stableIdentity(fixtures);
    AmbiguityProof ambiguity = ambiguity(fixtures);
    ReplayProof replay = replay(fixtures, reports.memoryDigest());
    ConflictProbeResult conflict = conflictingDuplicate(fixtures, reports.memoryDigest());
    return new DemoExecutionProof(
        identity,
        ambiguity,
        statelessSummary(reports.stateless()),
        memorySummary(reports.memory()),
        reports.statelessDigest(),
        replay.result(),
        conflict,
        replay.digestEquality());
  }

  private ReportPair reports(DemoFixtures fixtures) {
    BoundaryPosition e1 = finalizedPosition(fixtures.e1().trustedContext());
    BoundaryPosition e2 = finalizedPosition(fixtures.e2Planner().trustedContext());
    KnowledgeBoundary statelessBoundary = KnowledgeBoundary.of(List.of(e2));
    KnowledgeBoundary memoryBoundary = KnowledgeBoundary.of(List.of(e1, e2));
    Report stateless =
        jdbi.withHandle(
            handle ->
                reportService.statelessReport(
                    handle, fixtures.e2Planner().trustedContext(), statelessBoundary));
    Report memory =
        jdbi.withHandle(
            handle ->
                reportService.memoryReport(
                    handle, fixtures.e2Planner().trustedContext(), memoryBoundary));
    return new ReportPair(stateless, memory);
  }

  private BoundaryPosition finalizedPosition(TrustedContext context) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT source_stream_id, last_data_sequence FROM engagements"
                        + " WHERE tenant_id = :tenantId AND engagement_id = :engagementId")
                .bind("tenantId", context.tenantId())
                .bind("engagementId", context.engagementId())
                .map(
                    (rs, ctx) -> {
                      Long limit = rs.getObject("last_data_sequence", Long.class);
                      if (limit == null) {
                        throw new IllegalStateException("demo engagement is not finalized");
                      }
                      return new BoundaryPosition(rs.getString("source_stream_id"), limit);
                    })
                .findOne()
                .orElseThrow(
                    () -> new IllegalStateException("demo engagement is not recorded")));
  }

  private static void requireArmReports(SteeringArmEvidence steering, ReportPair reports) {
    if (!steering.memoryReportDigest().equals(reports.memoryDigest())) {
      throw new IllegalStateException("persisted memory report differs from arm evidence");
    }
    if (!steering.e2FactSetDigest().equals(reports.memory().currentEngagementFactDigest())
        || !reports.stateless().currentEngagementFactDigest()
            .equals(reports.memory().currentEngagementFactDigest())) {
      throw new IllegalStateException("stateless and memory views do not share the E2 facts");
    }
  }

  private StableIdentityProof stableIdentity(DemoFixtures fixtures) {
    List<IdentityRow> rows = identityRows(fixtures.e1().trustedContext().tenantId());
    if (rows.size() != 1) {
      throw new IllegalStateException("stable identity proof requires one cross-engagement asset");
    }
    IdentityRow row = rows.getFirst();
    return new StableIdentityProof(row.changed(), row.unchanged());
  }

  private List<IdentityRow> identityRows(String tenantId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT count(DISTINCT ao.canonical_asset_id) = 1 AS unchanged,
                      count(DISTINCT ao.pod_uid) > 1
                        AND count(DISTINCT ao.pod_name) > 1
                        AND count(DISTINCT ao.ip_address) > 1 AS changed
                    FROM asset_observations ao
                    JOIN source_events se USING (tenant_id, source_event_id)
                    WHERE ao.tenant_id = :tenantId AND ao.canonical_asset_id IS NOT NULL
                    GROUP BY ao.cluster_id, ao.resource_kind, ao.resource_uid
                    HAVING count(DISTINCT se.engagement_id) > 1
                    """)
                .bind("tenantId", tenantId)
                .map(
                    (rs, ctx) ->
                        new IdentityRow(rs.getBoolean("changed"), rs.getBoolean("unchanged")))
                .list());
  }

  private AmbiguityProof ambiguity(DemoFixtures fixtures) {
    List<AmbiguityRow> rows = ambiguityRows(fixtures.e1().trustedContext().tenantId());
    if (rows.size() != 1) {
      throw new IllegalStateException("ambiguity proof requires one unresolved observation");
    }
    AmbiguityRow row = rows.getFirst();
    return new AmbiguityProof(row.outcome(), row.postureDelta());
  }

  private List<AmbiguityRow> ambiguityRows(String tenantId) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    """
                    SELECT ao.resolution_outcome,
                      (SELECT count(*) FROM finding_occurrences fo
                        WHERE fo.tenant_id = ao.tenant_id
                          AND fo.source_event_id = ao.source_event_id)
                      + (SELECT count(*) FROM test_attempts ta
                        WHERE ta.tenant_id = ao.tenant_id
                          AND ta.source_event_id = ao.source_event_id) AS posture_delta
                    FROM asset_observations ao
                    WHERE ao.tenant_id = :tenantId AND ao.canonical_asset_id IS NULL
                    """)
                .bind("tenantId", tenantId)
                .map(
                    (rs, ctx) ->
                        new AmbiguityRow(
                            rs.getString("resolution_outcome"), rs.getInt("posture_delta")))
                .list());
  }

  private ReplayProof replay(DemoFixtures fixtures, String expectedDigest) {
    List<DemoDelivery> deliveries = new ArrayList<>(fixtures.deliveries());
    Collections.shuffle(deliveries, new Random(REPLAY_SHUFFLE_SEED));
    IngestResult observed = null;
    for (DemoDelivery delivery : deliveries) {
      IngestResult result = ingestor.ingest(delivery.context(), delivery.event());
      if (observed != null && observed != result) {
        throw new IllegalStateException("exact replay produced inconsistent results");
      }
      observed = result;
    }
    if (observed == null) {
      throw new IllegalStateException("exact replay requires source events");
    }
    return new ReplayProof(observed, expectedDigest.equals(reports(fixtures).memoryDigest()));
  }

  private ConflictProbeResult conflictingDuplicate(
      DemoFixtures fixtures, String expectedDigest) {
    DemoDelivery original = fixtures.deliveries().getFirst();
    CanonicalSourceEvent conflicting = modifiedContent(original.event());
    try {
      ingestor.ingest(original.context(), conflicting);
    } catch (SourceEventConflictException expected) {
      if (expected.reason() != SourceEventConflictException.Reason.EVENT_CONTENT) {
        throw new IllegalStateException("conflict probe reached the wrong contract", expected);
      }
      if (!expectedDigest.equals(reports(fixtures).memoryDigest())) {
        throw new IllegalStateException("conflicting duplicate changed the persisted report");
      }
      return ConflictProbeResult.EVENT_CONTENT_REJECTED;
    }
    throw new IllegalStateException("modified duplicate was unexpectedly accepted");
  }

  private CanonicalSourceEvent modifiedContent(CanonicalSourceEvent original) {
    try {
      ObjectNode node = (ObjectNode) objectMapper.readTree(original.canonicalJson());
      node.put("occurred_at", original.event().occurredAt().plusSeconds(1).toString());
      return codec.decode(objectMapper.writeValueAsString(node));
    } catch (IOException e) {
      throw new IllegalStateException("conflict probe event could not be encoded", e);
    }
  }

  private static StatelessReportSummary statelessSummary(Report report) {
    StatelessSummary summary = (StatelessSummary) report.summary();
    return new StatelessReportSummary(
        summary.detectedCount(), summary.notDetectedCount(), summary.partialCount());
  }

  private static MemoryReportSummary memorySummary(Report report) {
    MemorySummary summary = (MemorySummary) report.summary();
    return new MemoryReportSummary(
        summary.classificationCount(EpisodeClassification.NEW),
        summary.classificationCount(EpisodeClassification.STILL_OPEN),
        summary.classificationCount(EpisodeClassification.VERIFIED_RESOLVED),
        summary.classificationCount(EpisodeClassification.REGRESSED),
        summary.classificationCount(EpisodeClassification.NOT_RETESTED),
        summary.classificationCount(EpisodeClassification.INCONCLUSIVE));
  }

  private record IdentityRow(boolean changed, boolean unchanged) {}

  private record AmbiguityRow(String outcome, int postureDelta) {}

  private record ReplayProof(IngestResult result, boolean digestEquality) {}

  private record ReportPair(Report stateless, Report memory) {
    String statelessDigest() {
      return ReportRenderer.semanticDigest(stateless);
    }

    String memoryDigest() {
      return ReportRenderer.semanticDigest(memory);
    }
  }
}
