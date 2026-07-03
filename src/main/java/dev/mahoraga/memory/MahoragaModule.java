package dev.mahoraga.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import jakarta.validation.Validator;
import java.util.Objects;

/** The single explicit Guice root module for the application. */
public final class MahoragaModule extends AbstractModule {

  private final MahoragaConfiguration configuration;
  private final ObjectMapper objectMapper;
  private final Validator validator;

  public MahoragaModule(
      MahoragaConfiguration configuration, ObjectMapper objectMapper, Validator validator) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  @Override
  protected void configure() {
    // Just-in-time bindings are disabled so every injectable type must be
    // declared here; accidental implicit wiring fails fast.
    binder().requireExplicitBindings();
    bind(MahoragaConfiguration.class).toInstance(configuration);
    bind(ObjectMapper.class).toInstance(objectMapper);
    bind(Validator.class).toInstance(validator);
    bind(SourceEventValidator.class);
    bind(SourceEventCodec.class);
  }
}
