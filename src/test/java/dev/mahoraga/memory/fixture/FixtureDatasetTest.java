package dev.mahoraga.memory.fixture;

import static dev.mahoraga.memory.fixture.FixtureTestSupport.loadV1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.mahoraga.memory.contract.CanonicalEncoding;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.EventType;
import dev.mahoraga.memory.contract.SourceEvent;
import dev.mahoraga.memory.fixture.FixtureTestSupport.V1Bundle;
import dev.mahoraga.memory.fixture.RunnerManifest.RunnerCandidate;
import io.dropwizard.jackson.Jackson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the v1 fixture bundle is structurally sound and leakage-free without a
 * database: every resource parses and cross-validates through the production
 * loader, per-stream positions are contiguous with one marker, detected
 * candidates carry both their occurrence and attempt inputs, the planner-safe
 * projection exposes no runner vocabulary, and the semantic digest is unchanged
 * by formatting but changes with semantics.
 */
class FixtureDatasetTest {

  /**
   * Golden digest over all canonical source hashes in stream/sequence order
   * plus the canonical runner-manifest semantics. Update deliberately only
   * when a fixture event, candidate mapping, frozen outcome, or scenario
   * expectation intentionally changes.
   */
  private static final String GOLDEN_DIGEST =
      "dc7a83750b4d27f8b4a01a955ecd2197c41f7e780b59adcb5e272c57c445572c";

  private static final List<String> RUNNER_TOKENS =
      List.of("F-STILL", "F-FIXED", "F-REGRESS", "F-UNTESTED", "F-INCONCLUSIVE", "F-NEW",
          "T-A", "T-B", "T-C");

  private final V1Bundle bundle = loadV1();

  @Test
  void wholeBundleParsesAndCrossValidates() {
    assertEquals("engagement-1", bundle.e1().trustedContext().engagementId());
    assertEquals("engagement-2", bundle.e2Planner().trustedContext().engagementId());
    assertEquals(8, bundle.e1().events().size());
    assertEquals(6, bundle.e2Planner().events().size());
    assertEquals(3, bundle.e2Background().events().size());
    assertEquals(1, bundle.e2Completion().events().size());
    assertEquals(
        List.of("T-A", "T-B", "T-C"),
        bundle.manifest().candidates().stream().map(RunnerCandidate::candidateId).toList());
  }

  @Test
  void perStreamPositionsAreContiguousWithOneMarker() {
    assertContiguousStream(bundle.e1().events(), "stream-e1", 7);
    assertContiguousStream(allE2Events(), "stream-e2", 9);
  }

  @Test
  void detectedCandidatesReferenceBothOccurrenceAndAttemptInputs() {
    assertCandidateActionKinds("T-A", EventType.FINDING_OBSERVATION, EventType.TEST_ATTEMPT);
    assertCandidateActionKinds("T-C", EventType.FINDING_OBSERVATION, EventType.TEST_ATTEMPT);
    assertCandidateActionKinds("T-B", EventType.TEST_ATTEMPT);
  }

  @Test
  void sourcePayloadsCarryNoRunnerVocabulary() {
    for (CanonicalSourceEvent event : allEvents()) {
      String payload = event.canonicalPayloadJson();
      for (String token : RUNNER_TOKENS) {
        assertFalse(
            payload.contains(token),
            "event " + event.event().sourceEventId() + " payload leaked " + token);
      }
    }
  }

  @Test
  void plannerProjectionExposesNoRunnerVocabulary() throws Exception {
    PlannerCandidateSet projection =
        PlannerCandidateSet.from(bundle.e2Planner().trustedContext(), bundle.manifest());
    String serialized = Jackson.newObjectMapper().writeValueAsString(projection);

    for (String label : List.of("F-STILL", "F-FIXED", "F-REGRESS", "F-INCONCLUSIVE", "F-NEW")) {
      assertFalse(serialized.contains(label), "projection leaked label " + label);
    }
    assertFalse(serialized.contains("detected"), serialized);
    for (String eventId : List.of("e2-still", "e2-fixed-neg", "e2-regress")) {
      assertFalse(serialized.contains(eventId), "projection leaked event reference " + eventId);
    }
    assertTrue(serialized.contains("T-A"), serialized);
    assertTrue(serialized.contains("check-login-sqli"), serialized);
    assertTrue(serialized.contains("deploy-web"), serialized);
  }

  @Test
  void semanticDigestMatchesGoldenAndIsFormattingStable() {
    assertEquals(GOLDEN_DIGEST, digestOf(bundle));
    // Compact reserialization changes whitespace and line endings but not the
    // canonical source hashes or the sorted-key manifest semantics.
    assertEquals(GOLDEN_DIGEST, digestOf(loadV1(FixtureDatasetTest::reformat)));
  }

  @Test
  void semanticDigestIsSensitiveToSemanticChange() {
    RunnerManifest budgetChanged =
        new RunnerManifest(
            bundle.manifest().actionBudget() + 1,
            bundle.manifest().candidates(),
            bundle.manifest().background());
    assertNotEquals(GOLDEN_DIGEST, digest(allEvents(), budgetChanged));

    List<CanonicalSourceEvent> withoutOneEvent = allEvents();
    withoutOneEvent.remove(withoutOneEvent.size() - 1);
    assertNotEquals(GOLDEN_DIGEST, digest(withoutOneEvent, bundle.manifest()));
  }

  private static String digestOf(V1Bundle bundle) {
    List<CanonicalSourceEvent> events = new ArrayList<>(bundle.e1().events());
    events.addAll(bundle.e2Planner().events());
    events.addAll(bundle.e2Background().events());
    events.addAll(bundle.e2Completion().events());
    return digest(events, bundle.manifest());
  }

  /**
   * Lowercase SHA-256 over canonical source hashes in stream/sequence order
   * followed by the canonical runner-manifest semantics (sorted object keys,
   * preserved action-event order).
   */
  private static String digest(List<CanonicalSourceEvent> events, RunnerManifest manifest) {
    String hashes =
        events.stream()
            .sorted(
                Comparator.comparing((CanonicalSourceEvent e) -> e.event().sourceStreamId())
                    .thenComparingLong(e -> e.event().sourceSequence()))
            .map(CanonicalSourceEvent::canonicalSourceHash)
            .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
    String material = hashes + "\n---manifest---\n" + canonicalManifest(manifest);
    return CanonicalEncoding.sha256Hex(material.getBytes(StandardCharsets.UTF_8));
  }

  private static String canonicalManifest(RunnerManifest manifest) {
    ObjectMapper mapper =
        Jackson.newObjectMapper()
            .copy()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    try {
      return mapper.writeValueAsString(manifest);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("manifest failed to canonicalize", e);
    }
  }

  private static String reformat(String json) {
    ObjectMapper mapper = Jackson.newObjectMapper();
    try {
      return mapper.writeValueAsString(mapper.readTree(json));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("resource failed to reformat", e);
    }
  }

  private List<CanonicalSourceEvent> allEvents() {
    List<CanonicalSourceEvent> events = new ArrayList<>(bundle.e1().events());
    events.addAll(allE2Events());
    return events;
  }

  private List<CanonicalSourceEvent> allE2Events() {
    List<CanonicalSourceEvent> events = new ArrayList<>(bundle.e2Planner().events());
    events.addAll(bundle.e2Background().events());
    events.addAll(bundle.e2Completion().events());
    return events;
  }

  private void assertCandidateActionKinds(String candidateId, EventType... expectedKinds) {
    RunnerCandidate candidate =
        bundle.manifest().candidates().stream()
            .filter(c -> c.candidateId().equals(candidateId))
            .findFirst()
            .orElseThrow();
    List<EventType> kinds =
        candidate.actionEventIds().stream().map(this::eventTypeOf).toList();
    assertEquals(List.of(expectedKinds), kinds);
  }

  private EventType eventTypeOf(String sourceEventId) {
    return bundle.e2Referenced().events().stream()
        .map(CanonicalSourceEvent::event)
        .filter(event -> event.sourceEventId().equals(sourceEventId))
        .map(SourceEvent::eventType)
        .findFirst()
        .orElseThrow();
  }

  private static void assertContiguousStream(
      List<CanonicalSourceEvent> events, String streamId, long lastDataSequence) {
    List<SourceEvent> inStream =
        events.stream()
            .map(CanonicalSourceEvent::event)
            .filter(event -> event.sourceStreamId().equals(streamId))
            .sorted(Comparator.comparingLong(SourceEvent::sourceSequence))
            .toList();
    assertEquals(lastDataSequence + 1, inStream.size());
    for (int position = 1; position <= lastDataSequence; position++) {
      SourceEvent event = inStream.get(position - 1);
      assertEquals(position, event.sourceSequence());
      assertNotEquals(EventType.ENGAGEMENT_COMPLETED, event.eventType());
    }
    SourceEvent marker = inStream.get((int) lastDataSequence);
    assertEquals(lastDataSequence + 1, marker.sourceSequence());
    assertEquals(EventType.ENGAGEMENT_COMPLETED, marker.eventType());
    assertEquals(
        1,
        inStream.stream()
            .filter(event -> event.eventType() == EventType.ENGAGEMENT_COMPLETED)
            .count());
  }
}
