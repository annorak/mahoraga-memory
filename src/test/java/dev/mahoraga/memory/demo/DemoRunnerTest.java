package dev.mahoraga.memory.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.SourceEventValidator;
import dev.mahoraga.memory.demo.DemoArmEvidence.AmbiguityProof;
import dev.mahoraga.memory.demo.DemoArmEvidence.CompletionProbeResult;
import dev.mahoraga.memory.demo.DemoArmEvidence.ConflictProbeResult;
import dev.mahoraga.memory.demo.DemoArmEvidence.MemoryReportSummary;
import dev.mahoraga.memory.demo.DemoArmEvidence.StableIdentityProof;
import dev.mahoraga.memory.demo.DemoArmEvidence.StatelessReportSummary;
import dev.mahoraga.memory.fixture.CandidateActionSet;
import dev.mahoraga.memory.fixture.FixtureTestSupport;
import dev.mahoraga.memory.fixture.PlannerCandidateSet;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.ingest.IngestorTestSupport;
import dev.mahoraga.memory.planning.SteeringArmEvidence.ArmMode;
import dev.mahoraga.memory.planning.SteeringArmRunner;
import io.dropwizard.validation.BaseValidator;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Executes the complete demo proof through real PostgreSQL on isolated clean databases. */
class DemoRunnerTest {

  private static final String BUILD_FINGERPRINT = "a".repeat(64);

  private static DemoArmEvidence control;
  private static DemoArmEvidence repeatedControl;
  private static DemoArmEvidence memory;
  private static DemoEvidence comparison;

  @BeforeAll
  static void executeArms() throws SQLException {
    control = runner(IngestorTestSupport.forDatabase("demo_runner_control"))
        .runArm(ArmMode.CONTROL, BUILD_FINGERPRINT);
    repeatedControl = runner(IngestorTestSupport.forDatabase("demo_runner_control_repeat"))
        .runArm(ArmMode.CONTROL, BUILD_FINGERPRINT);
    memory = runner(IngestorTestSupport.forDatabase("demo_runner_memory"))
        .runArm(ArmMode.MEMORY, BUILD_FINGERPRINT);
    comparison = DemoEvidence.compare(control, memory, BUILD_FINGERPRINT);
  }

  @Test
  void armEvidenceDerivesTheCompleteApprovedProof() {
    for (DemoArmEvidence arm : List.of(control, memory)) {
      assertEquals(List.of("T-A", "T-B", "T-C"), arm.candidateIds());
      assertTrue(arm.hasZeroE2EventsAtPlanning());
      assertEquals(new StableIdentityProof(true, true), arm.stableIdentityProof());
      assertEquals(new AmbiguityProof("AMBIGUOUS", 0), arm.ambiguityProof());
      assertEquals(new StatelessReportSummary(3, 1, 1), arm.statelessReportSummary());
      assertEquals(new MemoryReportSummary(1, 1, 1, 1, 1, 1), arm.memoryReportSummary());
      assertEquals(IngestResult.NO_OP, arm.duplicateRetryResult());
      assertEquals(
          ConflictProbeResult.EVENT_CONTENT_REJECTED, arm.conflictingDuplicateResult());
      assertEquals(
          CompletionProbeResult.UNFINALIZED_REPORT_BLOCKED, arm.missingCompletionResult());
      assertTrue(arm.shuffleDigestEquality());
      assertFalse(arm.rollbackProbeHasPartialState());
    }
  }

  @Test
  void isolatedControlRunsProduceByteEqualNormalizedEvidence() {
    assertEquals(control, repeatedControl);
    assertEquals(
        DemoEvidence.canonicalJson(control), DemoEvidence.canonicalJson(repeatedControl));
  }

  @Test
  void pureComparisonProducesExactExecutedThreeToOneProof() {
    assertEquals(List.of("T-A", "T-B", "T-C"), comparison.controlExecutedOrder());
    assertEquals(List.of("T-C", "T-A", "T-B"), comparison.memoryExecutedOrder());
    assertEquals(3, comparison.controlActionsBeforeRegression());
    assertEquals(1, comparison.memoryActionsBeforeRegression());
    assertEquals(control.e2FactSetDigest(), comparison.e2FactSetDigest());
    assertEquals(control.memoryReportDigest(), comparison.memoryReportDigest());
    assertFalse(comparison.transactionFailureLeavesPartialState());
  }

  @Test
  void sourceLessApplicationStateRejectsTheArmBeforeTheProbe() throws SQLException {
    IngestorTestSupport dirty = IngestorTestSupport.forDatabase("demo_runner_dirty");
    dirty.jdbi.useHandle(
        handle ->
            handle
                .createUpdate(
                    "INSERT INTO engagements (tenant_id, engagement_id, source_stream_id)"
                        + " VALUES ('dirty', 'dirty', 'dirty')")
                .execute());
    var fixtures = FixtureTestSupport.loadV1();

    assertThrows(
        IllegalStateException.class,
        () -> executeSteeringArm(dirty, fixtures));
    assertThrows(
        IllegalStateException.class,
        () -> runner(dirty).runArm(ArmMode.CONTROL, BUILD_FINGERPRINT));
    assertEquals(0, dirty.count("source_events", "dirty"));
  }

  private static DemoRunner runner(IngestorTestSupport db) {
    SourceEventCodec codec =
        new SourceEventCodec(
            IngestorTestSupport.MAPPER,
            new SourceEventValidator(BaseValidator.newValidator()));
    return new DemoRunner(db.jdbi, IngestorTestSupport.MAPPER, codec, db.ingestor);
  }

  private static void executeSteeringArm(
      IngestorTestSupport db, FixtureTestSupport.V1Bundle fixtures) {
    CandidateActionSet actions =
        CandidateActionSet.from(fixtures.manifest(), fixtures.e2Planner());
    new SteeringArmRunner(db.jdbi, db.ingestor)
        .execute(
            ArmMode.CONTROL,
            fixtures.e1(),
            PlannerCandidateSet.from(
                fixtures.e2Planner().trustedContext(), fixtures.manifest()),
            actions,
            fixtures.e2Background(),
            fixtures.e2Completion());
  }

}
