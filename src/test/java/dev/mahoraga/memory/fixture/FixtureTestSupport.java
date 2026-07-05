package dev.mahoraga.memory.fixture;

import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

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
    return read("/fixtures/contracts/" + name);
  }

  static String v1(String name) {
    return read("/fixtures/v1/" + name);
  }

  /**
   * Loads and cross-validates the whole v1 bundle through the production loader.
   * The manifest is validated against the planner and background events it
   * references; the completion marker sits in its own event set.
   */
  static V1Bundle loadV1() {
    return loadV1(UnaryOperator.identity());
  }

  /**
   * Loads the bundle after applying {@code transform} to each raw resource, so
   * a test can prove that reformatted input yields the same typed values.
   */
  static V1Bundle loadV1(UnaryOperator<String> transform) {
    FixtureLoader loader = loader();
    FixtureEventSet e1 = loader.loadEventSet(transform.apply(v1("engagement-e1.json")));
    FixtureEventSet planner =
        loader.loadEventSet(transform.apply(v1("engagement-e2-planner-events.json")));
    FixtureEventSet background =
        loader.loadEventSet(transform.apply(v1("engagement-e2-background-events.json")));
    FixtureEventSet completion =
        loader.loadEventSet(transform.apply(v1("engagement-e2-completion.json")));
    List<CanonicalSourceEvent> referencedEvents = new ArrayList<>(planner.events());
    referencedEvents.addAll(background.events());
    FixtureEventSet referenced = new FixtureEventSet(planner.trustedContext(), referencedEvents);
    RunnerManifest manifest =
        loader.loadManifest(transform.apply(v1("runner-manifest.json")), referenced);
    return new V1Bundle(e1, planner, background, completion, referenced, manifest);
  }

  /** The loaded v1 fixture bundle: E1, the three E2 files, and the runner manifest. */
  record V1Bundle(
      FixtureEventSet e1,
      FixtureEventSet e2Planner,
      FixtureEventSet e2Background,
      FixtureEventSet e2Completion,
      FixtureEventSet e2Referenced,
      RunnerManifest manifest) {}

  private static String read(String resourcePath) {
    try (InputStream resource = FixtureTestSupport.class.getResourceAsStream(resourcePath)) {
      if (resource == null) {
        throw new IllegalArgumentException("no fixture resource at " + resourcePath);
      }
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
