package dev.mahoraga.memory.posture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.contract.SourcePayload.ExecutionStatus;
import dev.mahoraga.memory.contract.SourcePayload.TestResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the total domain order: validated effective time first, then stream,
 * position, and event id as deterministic tie-breakers, with equality only for
 * fully equal keys.
 */
class PostureOrderTest {

  private static final Instant EARLY = Instant.parse("2026-01-01T10:00:00Z");
  private static final Instant LATE = Instant.parse("2026-01-01T11:00:00Z");

  @Test
  void ordersByEffectiveTimeFirst() {
    assertTrue(compare(fact(EARLY, "s-b", 9, "evt-z"), fact(LATE, "s-a", 1, "evt-a")) < 0);
  }

  @Test
  void breaksEqualTimeByStream() {
    assertTrue(compare(fact(EARLY, "s-a", 9, "evt-z"), fact(EARLY, "s-b", 1, "evt-a")) < 0);
  }

  @Test
  void breaksEqualTimeAndStreamBySequence() {
    assertTrue(compare(fact(EARLY, "s-a", 1, "evt-z"), fact(EARLY, "s-a", 2, "evt-a")) < 0);
  }

  @Test
  void breaksEqualTimeStreamAndSequenceByEventId() {
    assertTrue(compare(fact(EARLY, "s-a", 1, "evt-a"), fact(EARLY, "s-a", 1, "evt-b")) < 0);
  }

  @Test
  void fullyEqualKeysCompareAsEqual() {
    assertEquals(0, compare(fact(EARLY, "s-a", 1, "evt-a"), fact(EARLY, "s-a", 1, "evt-a")));
  }

  private static int compare(SelectedFact left, SelectedFact right) {
    return PostureFolder.DOMAIN_ORDER.compare(left, right);
  }

  private static SelectedFact fact(Instant occurredAt, String streamId, long sequence,
      String eventId) {
    return new SelectedFact.TestAttempt(
        "t-order", "eng-1", eventId, streamId, sequence, occurredAt,
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "check-xss-1", "1.0", "a".repeat(64), 1,
        ExecutionStatus.COMPLETED, TestResult.NOT_DETECTED);
  }
}
