package dev.mahoraga.memory.ingest;

import dev.mahoraga.memory.boundary.EngagementCompletionHandler;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEvent;
import dev.mahoraga.memory.contract.SourcePayload.AssetObservation;
import dev.mahoraga.memory.contract.SourcePayload.EngagementCompleted;
import dev.mahoraga.memory.contract.SourcePayload.FindingObservation;
import dev.mahoraga.memory.contract.SourcePayload.TestAttempt;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.coverage.TestAttemptService;
import dev.mahoraga.memory.finding.FindingIdentityService;
import dev.mahoraga.memory.identity.AssetIdentityService;
import jakarta.inject.Inject;
import java.util.Objects;
import org.jdbi.v3.core.Handle;

/**
 * The single explicit composition of the four source-event routes: completion
 * prechecks, one exhaustive switch over the sealed payload contract into the
 * concrete domain handlers, and completion reevaluation, all inside the one
 * ingestion transaction so the source event, its domain facts, and any
 * finalization commit or roll back together. There is no registry, reflective
 * lookup, or asynchronous dispatch; a fifth payload type fails compilation
 * until this switch names it.
 */
public final class SourceEventIngestor {

  /**
   * Test-only fault seam invoked after domain work and completion
   * reevaluation on the same handle. Production binds {@link #NO_FAULTS};
   * tests inject a failure to prove every current-event write rolls back as
   * one unit.
   */
  @FunctionalInterface
  public interface IngestionFaultHook {
    void afterDomainWork(Handle handle);
  }

  public static final IngestionFaultHook NO_FAULTS = handle -> {};

  private final IngestionTransaction transaction;
  private final AssetIdentityService assetIdentityService;
  private final FindingIdentityService findingIdentityService;
  private final TestAttemptService testAttemptService;
  private final EngagementCompletionHandler completionHandler;
  private final IngestionFaultHook faultHook;

  @Inject
  public SourceEventIngestor(
      IngestionTransaction transaction,
      AssetIdentityService assetIdentityService,
      FindingIdentityService findingIdentityService,
      TestAttemptService testAttemptService,
      EngagementCompletionHandler completionHandler,
      IngestionFaultHook faultHook) {
    this.transaction = Objects.requireNonNull(transaction, "transaction");
    this.assetIdentityService =
        Objects.requireNonNull(assetIdentityService, "assetIdentityService");
    this.findingIdentityService =
        Objects.requireNonNull(findingIdentityService, "findingIdentityService");
    this.testAttemptService = Objects.requireNonNull(testAttemptService, "testAttemptService");
    this.completionHandler = Objects.requireNonNull(completionHandler, "completionHandler");
    this.faultHook = Objects.requireNonNull(faultHook, "faultHook");
  }

  /** An exact duplicate returns {@code NO_OP} inside the transaction before any work here runs. */
  public IngestResult ingest(TrustedContext context, CanonicalSourceEvent canonical) {
    return transaction.ingest(
        context, canonical, handle -> runDomainWork(handle, context, canonical.event()));
  }

  private void runDomainWork(Handle handle, TrustedContext context, SourceEvent event) {
    completionHandler.requireStreamAcceptsEvent(handle, context, event);
    String sourceEventId = event.sourceEventId();
    switch (event.payload()) {
      case AssetObservation payload ->
          assetIdentityService.recordAssetObservation(handle, context, sourceEventId, payload);
      case FindingObservation payload ->
          findingIdentityService.recordFindingObservation(handle, context, sourceEventId, payload);
      case TestAttempt payload ->
          testAttemptService.recordTestAttempt(handle, context, sourceEventId, payload);
      // Completeness is engagement state, not a domain fact; the marker stays
      // inbox-only and finalization happens in the shared reevaluation below.
      case EngagementCompleted marker -> {}
    }
    completionHandler.reevaluateCompletion(handle, context, event);
    faultHook.afterDomainWork(handle);
  }
}
