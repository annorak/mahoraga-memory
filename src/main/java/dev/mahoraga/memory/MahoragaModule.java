package dev.mahoraga.memory;

import com.google.inject.AbstractModule;
import java.util.Objects;

/** The single explicit Guice root module for the application. */
public final class MahoragaModule extends AbstractModule {

  private final MahoragaConfiguration configuration;

  public MahoragaModule(MahoragaConfiguration configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
  }

  @Override
  protected void configure() {
    // Just-in-time bindings are disabled so every injectable type must be
    // declared here; accidental implicit wiring fails fast.
    binder().requireExplicitBindings();
    bind(MahoragaConfiguration.class).toInstance(configuration);
  }
}
