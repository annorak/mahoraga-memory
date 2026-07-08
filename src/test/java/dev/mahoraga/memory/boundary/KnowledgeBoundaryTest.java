package dev.mahoraga.memory.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the boundary value and codec contract: caller order never affects
 * equality, canonical bytes, or the digest; invalid boundaries reject before
 * any use; and the canonical shape matches the fixed golden digest exactly.
 */
class KnowledgeBoundaryTest {

  private static final String GOLDEN_JSON =
      "{\"positions\":[{\"source_stream_id\":\"stream-e1\",\"last_data_sequence\":7},"
          + "{\"source_stream_id\":\"stream-e2\",\"last_data_sequence\":9}]}";
  private static final String GOLDEN_HASH =
      "69351b2fa8a942587ed7b17bbcf29ca524acbd5b6dee75dce056da94ad6a8634";

  @Test
  void callerPermutationGivesEqualValueBytesAndHash() {
    KnowledgeBoundary forward =
        KnowledgeBoundary.of(
            List.of(new BoundaryPosition("stream-e1", 7), new BoundaryPosition("stream-e2", 9)));
    KnowledgeBoundary reversed =
        KnowledgeBoundary.of(
            List.of(new BoundaryPosition("stream-e2", 9), new BoundaryPosition("stream-e1", 7)));

    assertEquals(forward, reversed);
    assertEquals(forward.hashCode(), reversed.hashCode());
    assertEquals(
        KnowledgeBoundaryCodec.canonicalJson(forward),
        KnowledgeBoundaryCodec.canonicalJson(reversed));
    assertEquals(KnowledgeBoundaryCodec.hash(forward), KnowledgeBoundaryCodec.hash(reversed));
  }

  @Test
  void canonicalJsonAndDigestMatchTheGolden() {
    KnowledgeBoundary boundary =
        KnowledgeBoundary.of(
            List.of(new BoundaryPosition("stream-e2", 9), new BoundaryPosition("stream-e1", 7)));

    assertEquals(GOLDEN_JSON, KnowledgeBoundaryCodec.canonicalJson(boundary));
    assertEquals(GOLDEN_HASH, KnowledgeBoundaryCodec.hash(boundary));
  }

  @Test
  void changingOneStreamOrOneLimitChangesTheDigest() {
    KnowledgeBoundary otherStream =
        KnowledgeBoundary.of(
            List.of(new BoundaryPosition("stream-e1", 7), new BoundaryPosition("stream-e3", 9)));
    KnowledgeBoundary otherLimit =
        KnowledgeBoundary.of(
            List.of(new BoundaryPosition("stream-e1", 7), new BoundaryPosition("stream-e2", 10)));

    assertNotEquals(GOLDEN_HASH, KnowledgeBoundaryCodec.hash(otherStream));
    assertNotEquals(GOLDEN_HASH, KnowledgeBoundaryCodec.hash(otherLimit));
  }

  @Test
  void invalidBoundariesRejectBeforeAnyUse() {
    assertThrows(IllegalArgumentException.class, () -> KnowledgeBoundary.of(null));
    assertThrows(IllegalArgumentException.class, () -> KnowledgeBoundary.of(List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> KnowledgeBoundary.of(Arrays.asList(new BoundaryPosition("stream-e1", 1), null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            KnowledgeBoundary.of(
                List.of(new BoundaryPosition("stream-e1", 1), new BoundaryPosition("stream-e1", 2))),
        "each stream appears exactly once");
  }

  @Test
  void invalidPositionsReject() {
    assertThrows(IllegalArgumentException.class, () -> new BoundaryPosition(null, 1));
    assertThrows(IllegalArgumentException.class, () -> new BoundaryPosition("  ", 1));
    assertThrows(IllegalArgumentException.class, () -> new BoundaryPosition("stream-e1", 0));
    assertThrows(IllegalArgumentException.class, () -> new BoundaryPosition("stream-e1", -3));
  }
}
