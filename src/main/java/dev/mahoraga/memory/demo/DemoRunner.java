package dev.mahoraga.memory.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.fixture.CandidateActionSet;
import dev.mahoraga.memory.fixture.FixtureEventSet;
import dev.mahoraga.memory.fixture.FixtureLoader;
import dev.mahoraga.memory.fixture.PlannerCandidateSet;
import dev.mahoraga.memory.fixture.RunnerManifest;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.ingest.SourceEventIngestor;
import dev.mahoraga.memory.planning.SteeringArmEvidence;
import dev.mahoraga.memory.planning.SteeringArmEvidence.ArmMode;
import dev.mahoraga.memory.planning.SteeringArmRunner;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jdbi.v3.core.Jdbi;

/** Executes one guarded synthetic arm and derives its normalized persisted evidence. */
public final class DemoRunner {

  private static final String FIXTURE_ROOT = "/fixtures/v1/";

  private final Jdbi jdbi;
  private final ObjectMapper objectMapper;
  private final SourceEventCodec codec;
  private final SourceEventIngestor ingestor;

  @Inject
  public DemoRunner(
      Jdbi jdbi,
      ObjectMapper objectMapper,
      SourceEventCodec codec,
      SourceEventIngestor ingestor) {
    this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.ingestor = Objects.requireNonNull(ingestor, "ingestor");
  }

  public DemoArmEvidence runArm(ArmMode mode, String buildFingerprint) {
    DemoFixtures fixtures = loadFixtures();
    DemoRollbackProof rollback =
        new DemoRollbackProbe(jdbi, objectMapper).execute(fixtures.e1());
    SteeringArmEvidence steering = executeArm(fixtures, mode);
    DemoExecutionProof proof =
        new DemoProofCollector(jdbi, ingestor, objectMapper, codec)
            .collect(fixtures, steering);
    DemoArmEvidence evidence = DemoArmEvidence.from(buildFingerprint, steering, proof, rollback);
    DemoEvidence.requireExpectedArm(evidence);
    return evidence;
  }

  private SteeringArmEvidence executeArm(DemoFixtures fixtures, ArmMode mode) {
    CandidateActionSet actions =
        CandidateActionSet.from(fixtures.manifest(), fixtures.e2Planner());
    PlannerCandidateSet candidates =
        PlannerCandidateSet.from(
            fixtures.e2Planner().trustedContext(), fixtures.manifest());
    return new SteeringArmRunner(jdbi, ingestor)
        .execute(
            mode,
            fixtures.e1(),
            candidates,
            actions,
            fixtures.e2Background(),
            fixtures.e2Completion());
  }

  private DemoFixtures loadFixtures() {
    FixtureLoader loader = new FixtureLoader(objectMapper, codec);
    FixtureEventSet e1 = loader.loadEventSet(readFixture("engagement-e1.json"));
    FixtureEventSet planner =
        loader.loadEventSet(readFixture("engagement-e2-planner-events.json"));
    FixtureEventSet background =
        loader.loadEventSet(readFixture("engagement-e2-background-events.json"));
    FixtureEventSet completion =
        loader.loadEventSet(readFixture("engagement-e2-completion.json"));
    FixtureEventSet referenced = referencedEvents(planner, background);
    RunnerManifest manifest =
        loader.loadManifest(readFixture("runner-manifest.json"), referenced);
    return new DemoFixtures(e1, planner, background, completion, manifest);
  }

  private static FixtureEventSet referencedEvents(
      FixtureEventSet planner, FixtureEventSet background) {
    List<CanonicalSourceEvent> events = new ArrayList<>(planner.events());
    events.addAll(background.events());
    return new FixtureEventSet(planner.trustedContext(), events);
  }

  private static String readFixture(String name) {
    try (InputStream input = DemoRunner.class.getResourceAsStream(FIXTURE_ROOT + name)) {
      if (input == null) {
        throw new IllegalStateException("missing synthetic fixture " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read synthetic fixture " + name, e);
    }
  }
}

record DemoFixtures(
    FixtureEventSet e1,
    FixtureEventSet e2Planner,
    FixtureEventSet e2Background,
    FixtureEventSet e2Completion,
    RunnerManifest manifest) {

  DemoFixtures {
    Objects.requireNonNull(e1, "e1");
    Objects.requireNonNull(e2Planner, "e2Planner");
    Objects.requireNonNull(e2Background, "e2Background");
    Objects.requireNonNull(e2Completion, "e2Completion");
    Objects.requireNonNull(manifest, "manifest");
  }

  List<DemoDelivery> deliveries() {
    List<DemoDelivery> deliveries = new ArrayList<>();
    for (FixtureEventSet eventSet : List.of(e1, e2Planner, e2Background, e2Completion)) {
      eventSet.events().forEach(
          event -> deliveries.add(new DemoDelivery(eventSet.trustedContext(), event)));
    }
    return deliveries;
  }
}

record DemoDelivery(
    dev.mahoraga.memory.contract.TrustedContext context, CanonicalSourceEvent event) {}

record DemoExecutionProof(
    DemoArmEvidence.StableIdentityProof stableIdentityProof,
    DemoArmEvidence.AmbiguityProof ambiguityProof,
    DemoArmEvidence.StatelessReportSummary statelessReportSummary,
    DemoArmEvidence.MemoryReportSummary memoryReportSummary,
    String statelessReportDigest,
    IngestResult duplicateRetryResult,
    DemoArmEvidence.ConflictProbeResult conflictingDuplicateResult,
    boolean shuffleDigestEquality) {}

record DemoRollbackProof(
    DemoArmEvidence.CompletionProbeResult missingCompletionResult, boolean hasPartialState) {}
