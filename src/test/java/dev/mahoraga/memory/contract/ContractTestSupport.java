package dev.mahoraga.memory.contract;

import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared construction and resource loading for contract tests. */
final class ContractTestSupport {

  private ContractTestSupport() {}

  static SourceEventCodec codec() {
    return new SourceEventCodec(
        Jackson.newObjectMapper(), new SourceEventValidator(BaseValidator.newValidator()));
  }

  static String contract(String name) {
    try {
      return Files.readString(Path.of("src", "test", "resources", "contracts", name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
