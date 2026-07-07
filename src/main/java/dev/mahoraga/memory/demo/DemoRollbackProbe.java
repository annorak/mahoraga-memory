package dev.mahoraga.memory.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.boundary.BoundaryPosition;
import dev.mahoraga.memory.boundary.EngagementCompletionHandler;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.EventType;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.coverage.TestAttemptService;
import dev.mahoraga.memory.demo.DemoArmEvidence.CompletionProbeResult;
import dev.mahoraga.memory.finding.FindingIdentityService;
import dev.mahoraga.memory.fixture.FixtureEventSet;
import dev.mahoraga.memory.identity.AssetIdentityService;
import dev.mahoraga.memory.ingest.IngestionFaultHook;
import dev.mahoraga.memory.ingest.IngestionTransaction;
import dev.mahoraga.memory.ingest.SourceEventInbox;
import dev.mahoraga.memory.ingest.SourceEventIngestor;
import dev.mahoraga.memory.reporting.ReportService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/** Runs the guarded one-shot rollback probe before normal fixture ingestion. */
final class DemoRollbackProbe {

  private static final List<String> TABLES =
      List.of(
          "engagements",
          "source_events",
          "assets",
          "asset_observations",
          "findings",
          "finding_occurrences",
          "test_attempts");

  private final Jdbi jdbi;
  private final ObjectMapper objectMapper;

  DemoRollbackProbe(Jdbi jdbi, ObjectMapper objectMapper) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  DemoRollbackProof execute(FixtureEventSet e1) {
    CanonicalSourceEvent event = firstDataEvent(e1);
    Map<String, List<String>> before = snapshot();
    requireEmpty(before);
    OneShotFault hook = new OneShotFault(e1.trustedContext(), event);
    try {
      faultedIngestor(hook).ingest(e1.trustedContext(), event);
      throw new IllegalStateException("synthetic rollback probe unexpectedly committed");
    } catch (RollbackProbeFailure expected) {
      // The named post-write fault must escape the transaction to force rollback.
    }
    if (!hook.hasFired()) {
      throw new IllegalStateException("synthetic rollback hook did not fire");
    }
    return new DemoRollbackProof(
        hook.completionResult(), !before.equals(snapshot()));
  }

  private SourceEventIngestor faultedIngestor(IngestionFaultHook hook) {
    AssetIdentityService assets = new AssetIdentityService(objectMapper, hook);
    return new SourceEventIngestor(
        new IngestionTransaction(jdbi, new SourceEventInbox()),
        assets,
        new FindingIdentityService(assets, hook),
        new TestAttemptService(assets, hook),
        new EngagementCompletionHandler(hook),
        hook);
  }

  private Map<String, List<String>> snapshot() {
    return jdbi.withHandle(
        handle -> {
          Map<String, List<String>> state = new LinkedHashMap<>();
          for (String table : TABLES) {
            List<String> rows =
                handle.createQuery("SELECT row_to_json(t)::text FROM " + table + " t")
                    .mapTo(String.class)
                    .list()
                    .stream()
                    .sorted()
                    .toList();
            state.put(table, rows);
          }
          return state;
        });
  }

  private static void requireEmpty(Map<String, List<String>> state) {
    if (state.values().stream().anyMatch(rows -> !rows.isEmpty())) {
      throw new IllegalStateException(
          "a demo arm requires an empty database but application state already exists");
    }
  }

  private static CanonicalSourceEvent firstDataEvent(FixtureEventSet e1) {
    CanonicalSourceEvent event = e1.events().getFirst();
    if (event.event().eventType() == EventType.ENGAGEMENT_COMPLETED) {
      throw new IllegalStateException("the first E1 fixture event must be a data event");
    }
    return event;
  }

  private static final class OneShotFault implements IngestionFaultHook {
    private final TrustedContext context;
    private final KnowledgeBoundary boundary;
    private boolean isArmed = true;
    private CompletionProbeResult completionResult;

    private OneShotFault(TrustedContext context, CanonicalSourceEvent event) {
      this.context = context;
      this.boundary =
          KnowledgeBoundary.of(
              List.of(
                  new BoundaryPosition(
                      event.event().sourceStreamId(), event.event().sourceSequence())));
    }

    @Override
    public void afterStage(Stage stage, Handle handle) {
      if (!isArmed || stage != Stage.BEFORE_TRANSACTION_RETURN) {
        return;
      }
      isArmed = false;
      completionResult = requireReportBlocked(handle);
      throw new RollbackProbeFailure();
    }

    private CompletionProbeResult requireReportBlocked(Handle handle) {
      try {
        new ReportService().memoryReport(handle, context, boundary);
      } catch (IllegalArgumentException expected) {
        if (expected.getMessage() != null && expected.getMessage().contains("is not finalized")) {
          return CompletionProbeResult.UNFINALIZED_REPORT_BLOCKED;
        }
        throw expected;
      }
      throw new IllegalStateException("an unfinalized stream unexpectedly produced a report");
    }

    boolean hasFired() {
      return !isArmed;
    }

    CompletionProbeResult completionResult() {
      return Objects.requireNonNull(completionResult, "completionResult");
    }
  }

  private static final class RollbackProbeFailure extends RuntimeException {}
}
