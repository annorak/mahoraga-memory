package dev.mahoraga.memory.planning;

import dev.mahoraga.memory.boundary.BoundaryFactQuery;
import dev.mahoraga.memory.boundary.BoundaryPosition;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.boundary.KnowledgeBoundaryCodec;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.fixture.CandidateActionSet;
import dev.mahoraga.memory.fixture.FixtureEventSet;
import dev.mahoraga.memory.fixture.PlannerCandidateSet;
import dev.mahoraga.memory.fixture.PlannerCandidateSet.PlannerCandidate;
import dev.mahoraga.memory.ingest.SourceEventIngestor;
import dev.mahoraga.memory.planning.SteeringArmEvidence.ArmMode;
import dev.mahoraga.memory.posture.SelectedFact;
import dev.mahoraga.memory.reporting.Report;
import dev.mahoraga.memory.reporting.ReportRenderer;
import dev.mahoraga.memory.reporting.ReportService;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jdbi.v3.core.Jdbi;

/**
 * Executes exactly one steering-experiment arm against the one clean database
 * its constructor received: replay and finalize E1, verify the pre-E2 planner
 * boundary, plan with or without derived memory features, ingest the plan's
 * frozen actions in returned order, ingest the remaining E2 events, finalize
 * E2, and derive semantic evidence from persisted state. It creates no second
 * pool or database and reuses the owning ingestion, planning, and reporting
 * services; the causative metric comes from recorded source-event linkage,
 * never from a candidate literal or plan position alone.
 */
public final class SteeringArmRunner {

  private final Jdbi jdbi;
  private final SourceEventIngestor ingestor;
  private final PreEngagementMemoryQuery memoryQuery = new PreEngagementMemoryQuery();
  private final DeterministicPlanner planner = new DeterministicPlanner();
  private final ReportService reportService = new ReportService();
  private final BoundaryFactQuery factQuery = new BoundaryFactQuery();

  @Inject
  public SteeringArmRunner(Jdbi jdbi, SourceEventIngestor ingestor) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    this.ingestor = Objects.requireNonNull(ingestor, "ingestor");
  }

  public SteeringArmEvidence execute(
      ArmMode mode,
      FixtureEventSet e1Events,
      PlannerCandidateSet candidates,
      CandidateActionSet actions,
      FixtureEventSet backgroundEvents,
      FixtureEventSet completionEvents) {
    Objects.requireNonNull(mode, "mode");
    requireConsistentInputs(e1Events, candidates, actions, backgroundEvents, completionEvents);
    requireCleanDatabase();
    TrustedContext e1Context = e1Events.trustedContext();
    TrustedContext e2Context = actions.trustedContext();
    ingest(e1Context, e1Events.events());
    KnowledgeBoundary e1Boundary = KnowledgeBoundary.of(List.of(finalizedPosition(e1Context)));
    boolean hasZeroE2EventsAtPlanning = countEventsOutsideEngagement(e1Context) == 0;
    if (!hasZeroE2EventsAtPlanning) {
      throw new IllegalStateException("E2 events exist before planning; the arm is invalid");
    }
    String e1SemanticDigest = reportDigest(e1Context, e1Boundary);
    Plan plan = plan(mode, candidates, e1Boundary, e2Context);
    Map<String, List<String>> executedEventIds = executePlan(plan, actions, e2Context);
    ingest(e2Context, actions.supportingEvents());
    ingest(e2Context, backgroundEvents.events());
    ingest(e2Context, completionEvents.events());
    PersistedOutcome outcome = persistedOutcome(e2Context, e1Boundary, plan, executedEventIds);
    return new SteeringArmEvidence(
        mode,
        ControlledInputDigest.of(candidates, actions, backgroundEvents, completionEvents),
        candidates.candidates().stream().map(PlannerCandidate::candidateId).toList(),
        plan.orderedCandidateIds(),
        executedEventIds,
        outcome.actionsBeforeRegression(),
        KnowledgeBoundaryCodec.hash(e1Boundary),
        hasZeroE2EventsAtPlanning,
        e1SemanticDigest,
        outcome.e2FactSetDigest(),
        outcome.memoryReportDigest());
  }

  /** Every post-execution value is derived from finalized persisted state. */
  private PersistedOutcome persistedOutcome(
      TrustedContext e2Context,
      KnowledgeBoundary e1Boundary,
      Plan plan,
      Map<String, List<String>> executedEventIds) {
    KnowledgeBoundary memoryBoundary =
        KnowledgeBoundary.of(List.of(e1Boundary.positions().get(0), finalizedPosition(e2Context)));
    requirePersistedSourceEvents(e2Context.tenantId(), executedEventIds);
    List<SelectedFact> facts =
        jdbi.withHandle(handle -> factQuery.selectFacts(handle, e2Context, memoryBoundary));
    int actionsBeforeRegression =
        CausativeRegressionMetric.actionsBeforeRegression(
            facts, e2Context.engagementId(), plan.orderedCandidateIds(), executedEventIds);
    Report memoryReport =
        jdbi.withHandle(handle -> reportService.memoryReport(handle, e2Context, memoryBoundary));
    return new PersistedOutcome(
        actionsBeforeRegression,
        memoryReport.currentEngagementFactDigest(),
        ReportRenderer.semanticDigest(memoryReport));
  }

  /** The metric and final digests one arm derives after full E2 persistence. */
  private record PersistedOutcome(
      int actionsBeforeRegression, String e2FactSetDigest, String memoryReportDigest) {}

  private Plan plan(
      ArmMode mode,
      PlannerCandidateSet candidates,
      KnowledgeBoundary e1Boundary,
      TrustedContext e2Context) {
    List<CandidateTest> candidateTests =
        candidates.candidates().stream()
            .map(
                candidate ->
                    new CandidateTest(
                        candidate.candidateId(), candidate.target(), candidate.verificationKey()))
            .toList();
    List<MemoryFeature> features =
        mode == ArmMode.MEMORY
            ? jdbi.withHandle(
                handle -> memoryQuery.deriveFeatures(handle, e2Context, e1Boundary, candidateTests))
            : List.of();
    return planner.plan(
        new PlannerRequest(
            candidates.tenantId(),
            candidateTests,
            candidates.actionBudget(),
            e1Boundary,
            features));
  }

  /** Ingests each planned candidate's frozen action events in plan order. */
  private Map<String, List<String>> executePlan(
      Plan plan, CandidateActionSet actions, TrustedContext e2Context) {
    Map<String, List<String>> executedEventIds = new LinkedHashMap<>();
    for (String candidateId : plan.orderedCandidateIds()) {
      List<CanonicalSourceEvent> events = actions.actionsFor(candidateId);
      ingest(e2Context, events);
      executedEventIds.put(
          candidateId, events.stream().map(event -> event.event().sourceEventId()).toList());
    }
    return executedEventIds;
  }

  private void ingest(TrustedContext context, List<CanonicalSourceEvent> events) {
    for (CanonicalSourceEvent event : events) {
      ingestor.ingest(context, event);
    }
  }

  /** An arm never resumes a used database; retry means a fresh empty target. */
  private void requireCleanDatabase() {
    long existing =
        jdbi.withHandle(
            handle ->
                handle.createQuery("SELECT count(*) FROM source_events").mapTo(Long.class).one());
    if (existing != 0) {
      throw new IllegalStateException(
          "a steering arm requires an empty database but found %d source events"
              .formatted(existing));
    }
  }

  /** The write-once finalized stream position of one engagement, or failure. */
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
                        throw new IllegalStateException(
                            "engagement %s is not finalized".formatted(context.engagementId()));
                      }
                      return new BoundaryPosition(rs.getString("source_stream_id"), limit);
                    })
                .findOne()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "engagement %s has no registered stream"
                                .formatted(context.engagementId()))));
  }

  private long countEventsOutsideEngagement(TrustedContext context) {
    return jdbi.withHandle(
        handle ->
            handle
                .createQuery(
                    "SELECT count(*) FROM source_events WHERE tenant_id = :tenantId"
                        + " AND engagement_id <> :engagementId")
                .bind("tenantId", context.tenantId())
                .bind("engagementId", context.engagementId())
                .mapTo(Long.class)
                .one());
  }

  /** The executed actions must exist as committed source rows, not just memory. */
  private void requirePersistedSourceEvents(
      String tenantId, Map<String, List<String>> executedEventIds) {
    List<String> ids = executedEventIds.values().stream().flatMap(List::stream).toList();
    long persisted =
        jdbi.withHandle(
            handle ->
                handle
                    .createQuery(
                        "SELECT count(*) FROM source_events WHERE tenant_id = :tenantId"
                            + " AND source_event_id = ANY(:ids)")
                    .bind("tenantId", tenantId)
                    .bindArray("ids", String.class, ids)
                    .mapTo(Long.class)
                    .one());
    if (persisted != ids.size()) {
      throw new IllegalStateException(
          "executed actions are not fully persisted: expected %d source events, found %d"
              .formatted(ids.size(), persisted));
    }
  }

  private String reportDigest(TrustedContext context, KnowledgeBoundary boundary) {
    return ReportRenderer.semanticDigest(
        jdbi.withHandle(handle -> reportService.memoryReport(handle, context, boundary)));
  }

  private static void requireConsistentInputs(
      FixtureEventSet e1Events,
      PlannerCandidateSet candidates,
      CandidateActionSet actions,
      FixtureEventSet backgroundEvents,
      FixtureEventSet completionEvents) {
    String tenantId = e1Events.trustedContext().tenantId();
    TrustedContext e2Context = actions.trustedContext();
    if (!candidates.tenantId().equals(tenantId) || !e2Context.tenantId().equals(tenantId)) {
      throw new IllegalArgumentException("all arm inputs must belong to one trusted tenant");
    }
    if (!backgroundEvents.trustedContext().equals(e2Context)
        || !completionEvents.trustedContext().equals(e2Context)) {
      throw new IllegalArgumentException("all E2 inputs must share one trusted context");
    }
    if (e1Events.trustedContext().engagementId().equals(e2Context.engagementId())) {
      throw new IllegalArgumentException("E1 and E2 must be distinct engagements");
    }
    for (PlannerCandidate candidate : candidates.candidates()) {
      actions.actionsFor(candidate.candidateId());
    }
  }
}
