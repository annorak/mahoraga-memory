package dev.mahoraga.memory.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.ingest.IngestorTestSupport;
import dev.mahoraga.memory.posture.SelectedFact;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves boundary fact selection against real PostgreSQL: visibility is
 * trusted tenant plus per-stream inclusive finalized positions, filtered
 * before any chronology; planner, memory, and stateless boundaries see exactly
 * their streams; standalone attempts survive without invented findings; and
 * multiple streams share one set-based statement per fact table.
 */
class BoundaryFactQueryTest {

  private static final String DATABASE = "boundary_facts";
  private static final String TENANT = "t-bound";
  private static final TrustedContext E1 = new TrustedContext(TENANT, "eng-1");
  private static final TrustedContext E2 = new TrustedContext(TENANT, "eng-2");
  private static final String STREAM_E1 = "stream-b-e1";
  private static final String STREAM_E2 = "stream-b-e2";
  private static final String BACKDATED_AT = "2026-01-01T08:00:00Z";

  private static IngestorTestSupport db;
  private static final BoundaryFactQuery query = new BoundaryFactQuery();

  /**
   * Engagement 1: a finding occurrence at position 1, then a backdated
   * compatible negative attempt at position 2, finalized at N = 2.
   * Engagement 2: one standalone completed negative attempt at position 1
   * with no finding observation, finalized at N = 1. Both streams reuse the
   * same position numbers, so isolation must be stream-local.
   */
  @BeforeAll
  static void migrateAndIngestScenario() throws SQLException {
    db = IngestorTestSupport.forDatabase(DATABASE);
    db.ingestor.ingest(E1, db.findingEvent("evt-b-e1-finding", STREAM_E1, 1));
    db.ingestor.ingest(E1, db.attemptEvent("evt-b-e1-attempt", STREAM_E1, 2, BACKDATED_AT));
    db.ingestor.ingest(E1, db.markerEvent("evt-b-e1-marker", STREAM_E1, 2));
    db.ingestor.ingest(E2, db.attemptEvent("evt-b-e2-attempt", STREAM_E2, 1));
    db.ingestor.ingest(E2, db.markerEvent("evt-b-e2-marker", STREAM_E2, 1));
  }

  @Test
  void plannerBoundaryContainsE1FactsAndZeroE2Facts() {
    List<SelectedFact> facts = select(TENANT, boundary(STREAM_E1, 2));

    assertEquals(
        List.of("evt-b-e1-finding", "evt-b-e1-attempt"),
        facts.stream().map(SelectedFact::sourceEventId).toList());
    assertTrue(facts.stream().allMatch(fact -> fact.engagementId().equals("eng-1")));
  }

  @Test
  void memoryBoundaryContainsE1AndE2Facts() {
    List<SelectedFact> facts =
        select(
            TENANT,
            KnowledgeBoundary.of(
                List.of(new BoundaryPosition(STREAM_E1, 2), new BoundaryPosition(STREAM_E2, 1))));

    assertEquals(3, facts.size());
    assertTrue(
        facts.stream().anyMatch(fact -> fact.sourceEventId().equals("evt-b-e2-attempt")),
        "the E2 attempt must be visible at the memory boundary");
  }

  @Test
  void e2OnlyBoundaryRetainsStandaloneAttemptsWithoutInventedFindings() {
    List<SelectedFact> facts = select(TENANT, boundary(STREAM_E2, 1));

    assertEquals(1, facts.size());
    SelectedFact.TestAttempt attempt = assertInstanceOf(SelectedFact.TestAttempt.class, facts.get(0));
    assertEquals("evt-b-e2-attempt", attempt.sourceEventId());
    assertEquals(TestResult.NOT_DETECTED, attempt.result());
  }

  @Test
  void backdatedEventAboveTheSelectedLimitStaysExcluded() {
    List<SelectedFact> facts = select(TENANT, boundary(STREAM_E1, 1));

    assertEquals(1, facts.size());
    SelectedFact.FindingOccurrence occurrence =
        assertInstanceOf(SelectedFact.FindingOccurrence.class, facts.get(0));
    // The excluded attempt occurred before the visible occurrence: source
    // position, never occurrence time, decides boundary visibility.
    assertTrue(Instant.parse(BACKDATED_AT).isBefore(occurrence.occurredAt()));
  }

  @Test
  void crossTenantUseReturnsNoRowsEvenWithKnownStreamIds() {
    List<SelectedFact> facts = select("t-bound-other", boundary(STREAM_E1, 2));

    assertEquals(List.of(), facts);
  }

  @Test
  void multipleStreamsShareOneStatementPerFactTable() {
    AtomicInteger statements = new AtomicInteger();
    SqlLogger counter =
        new SqlLogger() {
          @Override
          public void logBeforeExecution(StatementContext context) {
            statements.incrementAndGet();
          }
        };
    db.jdbi.setSqlLogger(counter);
    try {
      select(
          TENANT,
          KnowledgeBoundary.of(
              List.of(new BoundaryPosition(STREAM_E1, 2), new BoundaryPosition(STREAM_E2, 1))));
    } finally {
      db.jdbi.setSqlLogger(SqlLogger.NOP_SQL_LOGGER);
    }

    assertEquals(2, statements.get(), "one occurrence statement plus one attempt statement");
  }

  @Test
  void selectedFactsCarryTheDeclaredSemanticAndSourceFields() {
    List<SelectedFact> facts = select(TENANT, boundary(STREAM_E1, 2));

    SelectedFact.FindingOccurrence occurrence =
        assertInstanceOf(SelectedFact.FindingOccurrence.class, facts.get(0));
    assertEquals(TENANT, occurrence.tenantId());
    assertEquals("eng-1", occurrence.engagementId());
    assertEquals(STREAM_E1, occurrence.sourceStreamId());
    assertEquals(1, occurrence.sourceSequence());
    assertEquals("xss", occurrence.vulnClass());
    assertEquals("route:/login", occurrence.normalizedLocationSignature());
    assertEquals(1, occurrence.matchKeyVersion());
    assertEquals("check-xss-1", occurrence.verificationKey());
    assertEquals("1.0", occurrence.checkVersion());
    assertEquals(1, occurrence.compatibilityPolicyVersion());

    SelectedFact.TestAttempt attempt =
        assertInstanceOf(SelectedFact.TestAttempt.class, facts.get(1));
    assertEquals(ExecutionStatus.COMPLETED, attempt.executionStatus());
    assertEquals(TestResult.NOT_DETECTED, attempt.result());
    assertEquals(occurrence.canonicalAssetId(), attempt.canonicalAssetId());
    // Identical relevant context yields the identical stored fingerprint on
    // both sides of the future compatibility comparison.
    assertEquals(occurrence.relevantContextHash(), attempt.relevantContextHash());
  }

  @Test
  void restartYieldsEqualSemanticFactValues() throws SQLException {
    KnowledgeBoundary memory =
        KnowledgeBoundary.of(
            List.of(new BoundaryPosition(STREAM_E1, 2), new BoundaryPosition(STREAM_E2, 1)));
    List<SelectedFact> before = select(TENANT, memory);

    IngestorTestSupport restarted = IngestorTestSupport.forDatabase(DATABASE);
    List<SelectedFact> after =
        restarted.jdbi.withHandle(
            handle -> new BoundaryFactQuery().selectFacts(handle, new TrustedContext(TENANT, "eng-1"), memory));

    assertEquals(before, after);
  }

  private static KnowledgeBoundary boundary(String streamId, long limit) {
    return KnowledgeBoundary.of(List.of(new BoundaryPosition(streamId, limit)));
  }

  private static List<SelectedFact> select(String tenantId, KnowledgeBoundary boundary) {
    TrustedContext context = new TrustedContext(tenantId, "eng-1");
    return db.jdbi.withHandle(handle -> query.selectFacts(handle, context, boundary));
  }
}
