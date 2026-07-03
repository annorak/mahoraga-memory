package dev.mahoraga.memory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.inject.ConfigurationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.mahoraga.memory.contract.SourceEventCodec;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class MahoragaModuleTest {

  @Test
  void createsInjectorAndResolvesBoundInstances() {
    MahoragaConfiguration configuration = new MahoragaConfiguration();
    ObjectMapper objectMapper = Jackson.newObjectMapper();
    Validator validator = BaseValidator.newValidator();
    Injector injector =
        Guice.createInjector(new MahoragaModule(configuration, objectMapper, validator));

    assertSame(configuration, injector.getInstance(MahoragaConfiguration.class));
    assertSame(objectMapper, injector.getInstance(ObjectMapper.class));
    assertSame(validator, injector.getInstance(Validator.class));
    assertNotNull(injector.getInstance(SourceEventCodec.class));
  }

  @Test
  void rejectsJustInTimeBindings() {
    Injector injector = newInjector();

    assertThrows(ConfigurationException.class, () -> injector.getInstance(UnboundType.class));
  }

  @Test
  void rejectsProductionDependencyWithoutExplicitBinding() {
    Injector injector = newInjector();

    // JsonMapper has a public no-arg constructor, so only requireExplicitBindings
    // stands between it and accidental just-in-time injection.
    assertThrows(ConfigurationException.class, () -> injector.getInstance(JsonMapper.class));
  }

  private static Injector newInjector() {
    return Guice.createInjector(
        new MahoragaModule(
            new MahoragaConfiguration(), Jackson.newObjectMapper(), BaseValidator.newValidator()));
  }

  /** Constructable without configuration, so only explicit-binding enforcement rejects it. */
  static class UnboundType {}
}
