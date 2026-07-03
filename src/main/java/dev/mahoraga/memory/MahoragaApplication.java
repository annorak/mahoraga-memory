package dev.mahoraga.memory;

import com.google.inject.Guice;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Environment;

/** Entry point for the Mahoraga memory service. */
public class MahoragaApplication extends Application<MahoragaConfiguration> {

  public static void main(String[] args) throws Exception {
    new MahoragaApplication().run(args);
  }

  @Override
  public String getName() {
    return "mahoraga-memory";
  }

  @Override
  public void run(MahoragaConfiguration configuration, Environment environment) {
    // The single application injector. Later tasks obtain their components from
    // it and register them with Dropwizard explicitly.
    Guice.createInjector(
        new MahoragaModule(configuration, environment.getObjectMapper(), environment.getValidator()));
  }
}
