package dev.mahoraga.memory.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.MahoragaApplication;
import dev.mahoraga.memory.MahoragaConfiguration;
import dev.mahoraga.memory.boundary.BoundaryFactQuery;
import dev.mahoraga.memory.boundary.BoundaryPosition;
import dev.mahoraga.memory.boundary.KnowledgeBoundary;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.database.TestDatabase;
import dev.mahoraga.memory.fixture.FixtureEventSet;
import dev.mahoraga.memory.fixture.FixtureTestSupport;
import dev.mahoraga.memory.fixture.FixtureTestSupport.V1Bundle;
import dev.mahoraga.memory.identity.UnsupportedAssetIdentityException;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.ingest.IngestorTestSupport;
import dev.mahoraga.memory.ingest.SourceEventConflictException;
import dev.mahoraga.memory.ingest.SourceEventIngestor;
import dev.mahoraga.memory.posture.SelectedFact;
import dev.mahoraga.memory.reporting.ReportRenderer;
import dev.mahoraga.memory.reporting.ReportService;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The three distinct reproducibility guarantees over the v1 fixture bundle:
 * exact duplicate replay into the populated database is a pure no-op,
 * fixed-seed sequential shuffles into fresh databases converge to the baseline
 * canonical reports (internal UUIDs may differ; semantic bytes may not), and
 * same-database recomputation is a fresh read/fold/render inside a PostgreSQL
 * READ ONLY transaction — the narrowest real boundary proving no identity
 * matching upsert or any other write runs on that path. Canonical report
 * bytes fix the semantic digest, which TASK-011 owns and already proves.
 */
class ReplayReproducibilityTest {

  private static final String DATABASE = "replay_baseline";
  private static final String TENANT = "tenant-acme";
  private static final TrustedContext E2_CONTEXT = new TrustedContext(TENANT, "engagement-2");
  private static final KnowledgeBoundary MEMORY_BOUNDARY =
      KnowledgeBoundary.of(
          List.of(new BoundaryPosition("stream-e1", 7), new BoundaryPosition("stream-e2", 9)));
  private static final KnowledgeBoundary STATELESS_BOUNDARY =
      KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-e2", 9)));
  private static final List<Integer> SHUFFLE_SEEDS = List.of(7, 19, 42);
  private static final List<String> TABLES =
      List.of("engagements", "source_events", "assets", "asset_observations", "findings",
          "finding_occurrences", "test_attempts");

  private static final ReportService SERVICE = new ReportService();

  private static IngestorTestSupport db;
  private static V1Bundle bundle;
  private static String baselineMemoryJson;
  private static String baselineStatelessJson;
  private static List<UUID> baselineAssetIds;
  private static List<UUID> baselineFindingIds;

  @BeforeAll
  static void ingestOrderedBaseline() throws SQLException {
    db = IngestorTestSupport.forDatabase(DATABASE);
    bundle = FixtureTestSupport.loadV1();
    for (Delivery delivery : orderedDeliveries()) {
      db.ingestor.ingest(delivery.context(), delivery.event());
    }
    baselineMemoryJson = memoryJson(db.jdbi);
    baselineStatelessJson = statelessJson(db.jdbi);
    baselineAssetIds = recordedIds(db.jdbi, "assets", "canonical_asset_id");
    baselineFindingIds = recordedIds(db.jdbi, "findings", "finding_id");
  }

  @Test
  void orderedBaselineYieldsTheExpectedReportsWithStableDigests() {
    assertTrue(baselineMemoryJson.contains("\"NEW\":1"), "memory report must classify NEW once");
    assertTrue(baselineStatelessJson.contains("\"detected\":3,\"not_detected\":1,\"partial\":1"));
    // The semantic digest is SHA-256 of exactly these bytes, so it repeats too.
    assertEquals(baselineMemoryJson, memoryJson(db.jdbi));
  }

  @Test
  void duplicateReplayOfEveryEventIsANoOpWithNoStateChange() {
    List<Integer> countsBefore = tableCounts();
    for (Delivery delivery : orderedDeliveries()) {
      assertEquals(
          IngestResult.NO_OP,
          db.ingestor.ingest(delivery.context(), delivery.event()),
          "duplicate of " + delivery.event().event().sourceEventId() + " must be a no-op");
    }

    assertEquals(countsBefore, tableCounts());
    assertEquals(7L, db.boundary(TENANT, "engagement-1"));
    assertEquals(9L, db.boundary(TENANT, "engagement-2"));
    assertEquals(baselineAssetIds, recordedIds(db.jdbi, "assets", "canonical_asset_id"));
    assertEquals(baselineFindingIds, recordedIds(db.jdbi, "findings", "finding_id"));
    assertEquals(baselineMemoryJson, memoryJson(db.jdbi));
  }

  @Test
  void conflictingSameIdReplayIsRejectedAndChangesNothing() {
    List<Integer> countsBefore = tableCounts();
    // Same identity and position as the recorded e1-asset, different content;
    // only byte-equivalent retries are no-ops.
    CanonicalSourceEvent conflicting = db.assetEvent("e1-asset", "stream-e1", 1);
    assertThrows(
        SourceEventConflictException.class,
        () -> db.ingestor.ingest(new TrustedContext(TENANT, "engagement-1"), conflicting));

    assertEquals(countsBefore, tableCounts());
    assertEquals(baselineMemoryJson, memoryJson(db.jdbi));
  }

  @Test
  void fixedSeedShufflesIntoFreshDatabasesConvergeSemantically() throws SQLException {
    for (int seed : SHUFFLE_SEEDS) {
      IngestorTestSupport shuffled = IngestorTestSupport.forDatabase("replay_shuffle_" + seed);
      List<Delivery> deliveries = orderedDeliveries();
      Collections.shuffle(deliveries, new Random(seed));

      // A UID-less weak observation is rejected (committing nothing) until a
      // confirmed candidate exists; one bounded redelivery pass after the batch
      // models the source retrying it. Every other event ingests where it lands.
      List<Delivery> rejected = new ArrayList<>();
      for (Delivery delivery : deliveries) {
        try {
          shuffled.ingestor.ingest(delivery.context(), delivery.event());
        } catch (UnsupportedAssetIdentityException e) {
          rejected.add(delivery);
        }
      }
      for (Delivery delivery : rejected) {
        shuffled.ingestor.ingest(delivery.context(), delivery.event());
      }

      assertEquals(7L, shuffled.boundary(TENANT, "engagement-1"), "seed " + seed);
      assertEquals(9L, shuffled.boundary(TENANT, "engagement-2"), "seed " + seed);
      assertEquals(baselineMemoryJson, memoryJson(shuffled.jdbi), "seed " + seed);
      assertEquals(baselineStatelessJson, statelessJson(shuffled.jdbi), "seed " + seed);
      // Fresh databases mint their own random UUIDs; equality above is semantic.
      assertNotEquals(
          baselineFindingIds, recordedIds(shuffled.jdbi, "findings", "finding_id"),
          "seed " + seed);
    }
  }

  @Test
  void earlyCompletionMarkerCannotFinalizeUntilTheGapArrives() throws SQLException {
    IngestorTestSupport gapped = IngestorTestSupport.forDatabase("replay_gap");
    TrustedContext e1Context = bundle.e1().trustedContext();
    CanonicalSourceEvent gapEvent = null;
    for (CanonicalSourceEvent event : bundle.e1().events()) {
      if (event.event().sourceSequence() == 3) {
        gapEvent = event;
      } else {
        gapped.ingestor.ingest(e1Context, event);
      }
    }

    assertNull(gapped.boundary(TENANT, "engagement-1"), "a gapped stream must not finalize");
    assertThrows(
        IllegalArgumentException.class,
        () -> memoryJson(gapped.jdbi),
        "an unfinalized boundary must block the report");

    gapped.ingestor.ingest(e1Context, gapEvent);
    assertEquals(7L, gapped.boundary(TENANT, "engagement-1"));
    for (FixtureEventSet eventSet :
        List.of(bundle.e2Planner(), bundle.e2Background(), bundle.e2Completion())) {
      ingest(gapped.ingestor, eventSet);
    }
    assertEquals(baselineMemoryJson, memoryJson(gapped.jdbi));
  }

  @Test
  void overlappingSequenceNumbersNeverLeakAcrossStreams() {
    // Both streams use sequences 1..8; selection follows the boundary's stream.
    List<SelectedFact> statelessFacts = selectFacts(STATELESS_BOUNDARY);
    assertTrue(
        statelessFacts.stream().allMatch(fact -> fact.engagementId().equals("engagement-2")));
    List<SelectedFact> e1Facts =
        selectFacts(KnowledgeBoundary.of(List.of(new BoundaryPosition("stream-e1", 7))));
    assertTrue(e1Facts.stream().allMatch(fact -> fact.engagementId().equals("engagement-1")));
  }

  @Test
  void fullApplicationRestartBetweenBatchesConvergesToTheBaseline() throws Exception {
    String url = TestDatabase.ensureDatabase("replay_restart");
    // One full lifecycle ingests E1 plus a partial E2 batch; a complete stop
    // and a fresh Dropwizard/Guice/pool lifecycle then resumes E2.
    runApplication(url, List.of(bundle.e1(), bundle.e2Planner()));
    runApplication(url, List.of(bundle.e2Background(), bundle.e2Completion()));

    IngestorTestSupport reopened = IngestorTestSupport.forDatabase("replay_restart");
    assertEquals(baselineMemoryJson, memoryJson(reopened.jdbi));
    assertEquals(baselineStatelessJson, statelessJson(reopened.jdbi));
  }

  @Test
  void sameDatabaseRecomputationIsReadOnlyAndPreservesIdsAndBytes() {
    // READ ONLY makes PostgreSQL reject any write, so the insert-on-conflict
    // matching primitives cannot run on this path at all.
    String recomputedMemory = readOnly(handle ->
        ReportRenderer.canonicalJson(SERVICE.memoryReport(handle, E2_CONTEXT, MEMORY_BOUNDARY)));
    String recomputedStateless = readOnly(handle -> ReportRenderer.canonicalJson(
        SERVICE.statelessReport(handle, E2_CONTEXT, STATELESS_BOUNDARY)));

    assertEquals(baselineMemoryJson, recomputedMemory);
    assertEquals(baselineStatelessJson, recomputedStateless);
    assertEquals(baselineAssetIds, recordedIds(db.jdbi, "assets", "canonical_asset_id"));
    assertEquals(baselineFindingIds, recordedIds(db.jdbi, "findings", "finding_id"));
  }

  private static List<Delivery> orderedDeliveries() {
    List<Delivery> deliveries = new ArrayList<>();
    List<FixtureEventSet> sets =
        List.of(bundle.e1(), bundle.e2Planner(), bundle.e2Background(), bundle.e2Completion());
    sets.forEach(eventSet -> eventSet.events()
        .forEach(event -> deliveries.add(new Delivery(eventSet.trustedContext(), event))));
    return deliveries;
  }

  private static void ingest(SourceEventIngestor ingestor, FixtureEventSet eventSet) {
    for (CanonicalSourceEvent event : eventSet.events()) {
      ingestor.ingest(eventSet.trustedContext(), event);
    }
  }

  /** One complete Dropwizard start, batch ingestion, and shutdown. */
  private static void runApplication(String url, List<FixtureEventSet> batch) throws Exception {
    DropwizardTestSupport<MahoragaConfiguration> support =
        new DropwizardTestSupport<>(
            MahoragaApplication.class,
            ResourceHelpers.resourceFilePath("mahoraga-test.yml"),
            ConfigOverride.config("database.url", url),
            ConfigOverride.config("database.user", TestDatabase.username()),
            ConfigOverride.config("database.password", TestDatabase.password()));
    support.before();
    try {
      MahoragaApplication application = (MahoragaApplication) support.getApplication();
      SourceEventIngestor ingestor =
          application.getInjector().getInstance(SourceEventIngestor.class);
      batch.forEach(eventSet -> ingest(ingestor, eventSet));
    } finally {
      support.after();
    }
  }

  private static String readOnly(Function<Handle, String> render) {
    return db.jdbi.inTransaction(handle -> {
      handle.execute("SET TRANSACTION READ ONLY");
      return render.apply(handle);
    });
  }

  private static String memoryJson(Jdbi jdbi) {
    return jdbi.withHandle(handle ->
        ReportRenderer.canonicalJson(SERVICE.memoryReport(handle, E2_CONTEXT, MEMORY_BOUNDARY)));
  }

  private static String statelessJson(Jdbi jdbi) {
    return jdbi.withHandle(handle -> ReportRenderer.canonicalJson(
        SERVICE.statelessReport(handle, E2_CONTEXT, STATELESS_BOUNDARY)));
  }

  private static List<SelectedFact> selectFacts(KnowledgeBoundary boundary) {
    return db.jdbi.withHandle(handle ->
        new BoundaryFactQuery().selectFacts(handle, E2_CONTEXT, boundary));
  }

  private static List<UUID> recordedIds(Jdbi jdbi, String table, String idColumn) {
    String sql = "SELECT " + idColumn + " FROM " + table
        + " WHERE tenant_id = :tenantId ORDER BY " + idColumn;
    return jdbi.withHandle(handle ->
        handle.createQuery(sql).bind("tenantId", TENANT).mapTo(UUID.class).list());
  }

  private static List<Integer> tableCounts() {
    return TABLES.stream().map(table -> db.count(table, TENANT)).toList();
  }

  /** One fixture event with the trusted context of its event set, in authored order. */
  private record Delivery(TrustedContext context, CanonicalSourceEvent event) {}
}
