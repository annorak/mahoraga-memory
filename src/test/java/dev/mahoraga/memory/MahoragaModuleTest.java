package dev.mahoraga.memory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.ConfigurationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.junit.jupiter.api.Test;

class MahoragaModuleTest {

  @Test
  void createsInjectorAndResolvesBoundConfigurationInstance() {
    MahoragaConfiguration configuration = new MahoragaConfiguration();
    Injector injector = Guice.createInjector(new MahoragaModule(configuration));

    assertNotNull(injector);
    assertSame(configuration, injector.getInstance(MahoragaConfiguration.class));
  }

  @Test
  void rejectsJustInTimeBindings() {
    Injector injector = Guice.createInjector(new MahoragaModule(new MahoragaConfiguration()));

    assertThrows(ConfigurationException.class, () -> injector.getInstance(UnboundType.class));
  }

  @Test
  void rejectsProductionDependencyWithoutExplicitBinding() {
    Injector injector = Guice.createInjector(new MahoragaModule(new MahoragaConfiguration()));

    // ObjectMapper has a public no-arg constructor, so only requireExplicitBindings
    // stands between it and accidental just-in-time injection.
    assertThrows(ConfigurationException.class, () -> injector.getInstance(ObjectMapper.class));
  }

  /** Constructable without configuration, so only explicit-binding enforcement rejects it. */
  static class UnboundType {}
}
