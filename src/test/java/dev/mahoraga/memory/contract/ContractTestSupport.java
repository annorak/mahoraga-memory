package dev.mahoraga.memory.contract;

import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Shared construction and resource loading for contract tests. */
final class ContractTestSupport {

  private ContractTestSupport() {}

  static SourceEventCodec codec() {
    return new SourceEventCodec(
        Jackson.newObjectMapper(), new SourceEventValidator(BaseValidator.newValidator()));
  }

  static String contract(String name) {
    try (InputStream resource =
        ContractTestSupport.class.getResourceAsStream("/contracts/" + name)) {
      if (resource == null) {
        throw new IllegalArgumentException("no contract resource named " + name);
      }
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
