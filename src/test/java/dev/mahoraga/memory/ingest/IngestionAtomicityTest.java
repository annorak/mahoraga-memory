package dev.mahoraga.memory.ingest;

import static dev.mahoraga.memory.ingest.IngestionFaultHook.Stage.AFTER_ASSET_OBSERVATION_WRITE;
import static dev.mahoraga.memory.ingest.IngestionFaultHook.Stage.AFTER_CANONICAL_ASSET_RESOLUTION;
import static dev.mahoraga.memory.ingest.IngestionFaultHook.Stage.AFTER_ENGAGEMENT_FINALIZATION_WRITE;
import static dev.mahoraga.memory.ingest.IngestionFaultHook.Stage.AFTER_FINDING_OCCURRENCE_WRITE;
import static dev.mahoraga.memory.ingest.IngestionFaultHook.Stage.AFTER_FINDING_RESOLUTION;
import static dev.mahoraga.memory.ingest.IngestionFaultHook.Stage.AFTER_SOURCE_EVENT_INSERT;
import static dev.mahoraga.memory.ingest.IngestionFaultHook.Stage.AFTER_TEST_ATTEMPT_WRITE;
import static dev.mahoraga.memory.ingest.IngestionFaultHook.Stage.BEFORE_TRANSACTION_RETURN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codahale.metrics.MetricRegistry;
import dev.mahoraga.memory.contract.CanonicalEncoding;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.database.TestDatabase;
import dev.mahoraga.memory.ingest.IngestionFaultHook.Stage;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.util.Duration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The rollback matrix over real PostgreSQL: one deterministic failure at every
 * durable write stage of every applicable event type must leave all seven
 * tables byte-identical to the pre-operation state (including internal UUIDs
 * generated during the rolled-back attempt), the exact retry must commit once,
 * and a second retry must be the normal no-op. Every operation runs through a
 * pool of exactly one connection with a bounded borrow wait, so a handle
 * leaked on any path stalls the suite instead of passing silently.
 */
class IngestionAtomicityTest {

  private static final String DATABASE = "ingestion_atomicity";
  private static final List<String> TABLES =
      List.of("engagements", "source_events", "assets", "asset_observations", "findings",
          "finding_occurrences", "test_attempts");

  private static ManagedDataSource dataSource;
  private static ArmableFaultHook hook;
  private static IngestorTestSupport db;

  @BeforeAll
  static void migrateAndConnectThroughPoolOfOne() throws Exception {
    String url = TestDatabase.ensureDatabase(DATABASE);
    Flyway.configure()
        .dataSource(url, TestDatabase.username(), TestDatabase.password())
        .load()
        .migrate();
    DataSourceFactory factory = new DataSourceFactory();
    factory.setDriverClass("org.postgresql.Driver");
    factory.setUrl(url);
    factory.setUser(TestDatabase.username());
    factory.setPassword(TestDatabase.password());
    factory.setInitialSize(1);
    factory.setMinSize(1);
    factory.setMaxSize(1);
    factory.setMaxWaitForConnection(Duration.seconds(5));
    dataSource = factory.build(new MetricRegistry(), "ingestion-atomicity");
    dataSource.start();
    hook = new ArmableFaultHook();
    db = IngestorTestSupport.forMigratedDatabase(Jdbi.create(dataSource), hook);
  }

  @AfterAll
  static void closePool() throws Exception {
    dataSource.stop();
  }

  @Test
  void assetObservationWithAssetCreationRollsBackAtEveryApplicableStage() {
    for (Stage stage : List.of(AFTER_SOURCE_EVENT_INSERT, AFTER_CANONICAL_ASSET_RESOLUTION,
        AFTER_ASSET_OBSERVATION_WRITE, BEFORE_TRANSACTION_RETURN)) {
      Ids ids = Ids.of("asset", stage);
      assertStageRollsBackThenRetryCommits(
          ids.context(), db.assetEvent(ids.event(), ids.stream(), 1), stage);
      assertEquals(1, db.count("assets", ids.context().tenantId()));
      assertEquals(1, db.count("asset_observations", ids.context().tenantId()));
    }
  }

  @Test
  void findingObservationCreatingNewFindingRollsBackAtEveryApplicableStage() {
    for (Stage stage : List.of(AFTER_SOURCE_EVENT_INSERT, AFTER_CANONICAL_ASSET_RESOLUTION,
        AFTER_FINDING_RESOLUTION, AFTER_FINDING_OCCURRENCE_WRITE, BEFORE_TRANSACTION_RETURN)) {
      Ids ids = Ids.of("newfind", stage);
      assertStageRollsBackThenRetryCommits(
          ids.context(), db.findingEvent(ids.event(), ids.stream(), 1), stage);
      assertEquals(1, db.count("assets", ids.context().tenantId()));
      assertEquals(1, db.count("findings", ids.context().tenantId()));
      assertEquals(1, db.count("finding_occurrences", ids.context().tenantId()));
    }
  }

  @Test
  void findingObservationReusingExistingFindingRollsBackWithoutMutatingIt() {
    for (Stage stage : List.of(AFTER_FINDING_RESOLUTION, AFTER_FINDING_OCCURRENCE_WRITE,
        BEFORE_TRANSACTION_RETURN)) {
      Ids ids = Ids.of("reuse", stage);
      db.ingestor.ingest(ids.context(), db.findingEvent(ids.event() + "-seed", ids.stream(), 1));
      assertStageRollsBackThenRetryCommits(
          ids.context(), db.findingEvent(ids.event(), ids.stream(), 2), stage);
      // Same match identity: the retry appends a second occurrence to the one
      // recorded finding instead of creating or mutating a finding row.
      assertEquals(1, db.count("findings", ids.context().tenantId()));
      assertEquals(2, db.count("finding_occurrences", ids.context().tenantId()));
    }
  }

  @Test
  void testAttemptRollsBackAtEveryApplicableStage() {
    for (Stage stage : List.of(AFTER_SOURCE_EVENT_INSERT, AFTER_CANONICAL_ASSET_RESOLUTION,
        AFTER_TEST_ATTEMPT_WRITE, BEFORE_TRANSACTION_RETURN)) {
      Ids ids = Ids.of("attempt", stage);
      assertStageRollsBackThenRetryCommits(
          ids.context(), db.attemptEvent(ids.event(), ids.stream(), 1), stage);
      assertEquals(1, db.count("test_attempts", ids.context().tenantId()));
    }
  }

  @Test
  void engagementFinalizationRollsBackWithItsMarkerAndFinalizesOnRetry() {
    for (Stage stage : List.of(AFTER_SOURCE_EVENT_INSERT, AFTER_ENGAGEMENT_FINALIZATION_WRITE,
        BEFORE_TRANSACTION_RETURN)) {
      Ids ids = Ids.of("complete", stage);
      db.ingestor.ingest(ids.context(), db.assetEvent(ids.event() + "-data", ids.stream(), 1));
      // The pre-failure snapshot holds a null boundary, so the state equality
      // inside the helper also proves the rolled-back marker finalized nothing.
      assertStageRollsBackThenRetryCommits(
          ids.context(), db.markerEvent(ids.event(), ids.stream(), 1), stage);
      assertEquals(1L, db.boundary(ids.context().tenantId(), "eng-1"));
    }
  }

  @Test
  void databaseConstraintErrorsRollBackTheWholeOperationAndRetryCommits() {
    TrustedContext unique = new TrustedContext("t-atom-ck-unique", "eng-1");
    hook.armAction(AFTER_FINDING_OCCURRENCE_WRITE, handle -> handle
        .createUpdate("INSERT INTO finding_occurrences (tenant_id, source_event_id, finding_id)"
            + " SELECT tenant_id, source_event_id, finding_id FROM finding_occurrences"
            + " WHERE tenant_id = :tenantId")
        .bind("tenantId", unique.tenantId())
        .execute());
    assertRollsBackThenRetryCommitsOnce(unique,
        db.findingEvent("evt-atom-ck-unique", "stream-atom-ck-unique", 1),
        "pk_finding_occurrences");

    TrustedContext foreignKey = new TrustedContext("t-atom-ck-fk", "eng-1");
    hook.armAction(AFTER_FINDING_RESOLUTION, handle -> handle
        .createUpdate("INSERT INTO finding_occurrences (tenant_id, source_event_id, finding_id)"
            + " VALUES (:tenantId, 'evt-atom-ck-fk', :findingId)")
        .bind("tenantId", foreignKey.tenantId())
        .bind("findingId", UUID.randomUUID())
        .execute());
    assertRollsBackThenRetryCommitsOnce(foreignKey,
        db.findingEvent("evt-atom-ck-fk", "stream-atom-ck-fk", 1),
        "fk_finding_occurrences_finding");

    TrustedContext check = new TrustedContext("t-atom-ck-check", "eng-1");
    hook.armAction(AFTER_TEST_ATTEMPT_WRITE, handle -> handle
        .createUpdate("UPDATE test_attempts SET execution_status = 'failed'"
            + " WHERE tenant_id = :tenantId")
        .bind("tenantId", check.tenantId())
        .execute());
    assertRollsBackThenRetryCommitsOnce(check,
        db.attemptEvent("evt-atom-ck-check", "stream-atom-ck-check", 1),
        "ck_test_attempts_outcome_pairing");
  }

  @Test
  void restartAfterRollbackSeesNothingAndRestartAfterCommitStaysNoOp() throws Exception {
    TrustedContext context = new TrustedContext("t-atom-restart", "eng-1");
    CanonicalSourceEvent event = db.findingEvent("evt-atom-restart", "stream-atom-restart", 1);
    hook.arm(BEFORE_TRANSACTION_RETURN);
    assertThrows(IllegalStateException.class, () -> db.ingestor.ingest(context, event));

    // Fresh connections and a fresh composition: outcomes must come from
    // durable rows alone.
    IngestorTestSupport restarted = IngestorTestSupport.forDatabase(DATABASE);
    assertFalse(restarted.eventExists(context.tenantId(), "evt-atom-restart"));
    assertEquals(0, restarted.count("findings", context.tenantId()));
    assertEquals(IngestResult.ACCEPTED, restarted.ingestor.ingest(context, event));

    IngestorTestSupport secondRestart = IngestorTestSupport.forDatabase(DATABASE);
    assertEquals(IngestResult.NO_OP, secondRestart.ingestor.ingest(context, event));
    assertEquals(1, secondRestart.count("finding_occurrences", context.tenantId()));
  }

  private void assertStageRollsBackThenRetryCommits(
      TrustedContext context, CanonicalSourceEvent event, Stage stage) {
    hook.arm(stage);
    assertRollsBackThenRetryCommitsOnce(context, event, stage.name());
  }

  /**
   * The core matrix step: capture state, fail once, prove zero durable change
   * (row counts, every column including generated UUIDs, and the canonical
   * digest), then prove the exact retry commits once and again no-ops.
   */
  private void assertRollsBackThenRetryCommitsOnce(
      TrustedContext context, CanonicalSourceEvent event, String expectedErrorFragment) {
    Map<String, List<String>> before = snapshot();
    Exception failure = assertThrows(Exception.class, () -> db.ingestor.ingest(context, event));
    String message = fullMessage(failure);
    assertTrue(message.contains(expectedErrorFragment),
        "error must carry failure context, but was: " + message);
    assertFalse(message.contains("cluster-demo"), "error must not carry payload content");
    assertFalse(hook.isArmed(), "the armed fault must have fired");
    Map<String, List<String>> after = snapshot();
    assertEquals(before, after, "every table must match the pre-operation state");
    assertEquals(digest(before), digest(after), "semantic digest must be unchanged");
    assertEquals(IngestResult.ACCEPTED, db.ingestor.ingest(context, event));
    assertEquals(IngestResult.NO_OP, db.ingestor.ingest(context, event));
  }

  /** Full-fidelity rows of all seven tables, deterministically ordered. */
  private Map<String, List<String>> snapshot() {
    return db.jdbi.withHandle(handle -> {
      Map<String, List<String>> rows = new LinkedHashMap<>();
      for (String table : TABLES) {
        rows.put(table,
            handle.createQuery("SELECT row_to_json(t)::text FROM " + table + " t")
                .mapTo(String.class).list().stream().sorted().toList());
      }
      return rows;
    });
  }

  private static String digest(Map<String, List<String>> snapshot) {
    return CanonicalEncoding.sha256Hex(snapshot.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String fullMessage(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      messages.append(current.getMessage()).append('\n');
    }
    return messages.toString();
  }

  /** Disjoint tenant, stream, and event identifiers for one scenario and stage. */
  private record Ids(TrustedContext context, String stream, String event) {
    static Ids of(String scenario, Stage stage) {
      String suffix = "atom-%s-%d".formatted(scenario, stage.ordinal());
      return new Ids(
          new TrustedContext("t-" + suffix, "eng-1"), "stream-" + suffix, "evt-" + suffix);
    }
  }

  /**
   * One-shot armed fault: fires exactly at the armed stage and disarms itself,
   * so the following exact retry runs against the same composition unfaulted.
   */
  private static final class ArmableFaultHook implements IngestionFaultHook {
    private Stage armedStage;
    private Consumer<Handle> action;

    void arm(Stage stage) {
      armAction(stage, handle -> {
        throw new IllegalStateException("injected failure at " + stage);
      });
    }

    void armAction(Stage stage, Consumer<Handle> faultAction) {
      this.armedStage = stage;
      this.action = faultAction;
    }

    boolean isArmed() {
      return armedStage != null;
    }

    @Override
    public void afterStage(Stage stage, Handle handle) {
      if (stage != armedStage) {
        return;
      }
      Consumer<Handle> firing = action;
      armedStage = null;
      action = null;
      firing.accept(handle);
    }
  }
}
