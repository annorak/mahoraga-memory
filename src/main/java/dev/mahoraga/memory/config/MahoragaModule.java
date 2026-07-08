package dev.mahoraga.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import dev.mahoraga.memory.boundary.EngagementCompletionHandler;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.coverage.TestAttemptService;
import dev.mahoraga.memory.demo.DemoRunner;
import dev.mahoraga.memory.finding.FindingIdentityService;
import dev.mahoraga.memory.identity.AssetIdentityService;
import dev.mahoraga.memory.ingest.IngestionFaultHook;
import dev.mahoraga.memory.ingest.IngestionTransaction;
import dev.mahoraga.memory.ingest.SourceEventInbox;
import dev.mahoraga.memory.ingest.SourceEventIngestor;
import jakarta.validation.Validator;
import java.util.Objects;
import org.jdbi.v3.core.Jdbi;

/** The single explicit Guice root module for the application. */
public final class MahoragaModule extends AbstractModule {

  private final MahoragaConfiguration configuration;
  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final Jdbi jdbi;

  public MahoragaModule(
      MahoragaConfiguration configuration,
      ObjectMapper objectMapper,
      Validator validator,
      Jdbi jdbi) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
  }

  @Override
  protected void configure() {
    // Just-in-time bindings are disabled so every injectable type must be
    // declared here; accidental implicit wiring fails fast.
    binder().requireExplicitBindings();
    bind(MahoragaConfiguration.class).toInstance(configuration);
    bind(ObjectMapper.class).toInstance(objectMapper);
    bind(Validator.class).toInstance(validator);
    // The Jdbi instance is built by the application over the one managed data
    // source; consumers open scoped handles and transactions from it.
    bind(Jdbi.class).toInstance(jdbi);
    bind(SourceEventValidator.class);
    bind(SourceEventCodec.class);
    bind(SourceEventInbox.class);
    bind(IngestionTransaction.class);
    // Production ingestion runs with the no-op fault hook at every stage; only
    // tests bind a failing hook to prove single-transaction rollback.
    bind(IngestionFaultHook.class).toInstance(IngestionFaultHook.NO_FAULTS);
    bind(AssetIdentityService.class);
    bind(FindingIdentityService.class);
    bind(TestAttemptService.class);
    bind(EngagementCompletionHandler.class);
    bind(SourceEventIngestor.class);
    bind(DemoRunner.class);
  }
}
