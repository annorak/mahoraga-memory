package dev.mahoraga.memory.fixture;

import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Shared loader construction and resource loading for fixture-contract tests. */
final class FixtureTestSupport {

  private FixtureTestSupport() {}

  static FixtureLoader loader() {
    var mapper = Jackson.newObjectMapper();
    var codec =
        new SourceEventCodec(mapper, new SourceEventValidator(BaseValidator.newValidator()));
    return new FixtureLoader(mapper, codec);
  }

  static String fixture(String name) {
    try (InputStream resource =
        FixtureTestSupport.class.getResourceAsStream("/fixtures/contracts/" + name)) {
      if (resource == null) {
        throw new IllegalArgumentException("no fixture resource named " + name);
      }
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
