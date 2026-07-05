package dev.mahoraga.memory.fixture;

import static dev.mahoraga.memory.fixture.FixtureTestSupport.fixture;
import static dev.mahoraga.memory.fixture.FixtureTestSupport.loader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.InvalidSourceEventException;
import io.dropwizard.jackson.Jackson;
import java.util.List;
import org.junit.jupiter.api.Test;

class FixtureLoaderTest {

  private final FixtureLoader loader = loader();

  @Test
  void validBundleParsesStrictly() {
    FixtureEventSet eventSet = loader.loadEventSet(fixture("event-set.json"));
    assertEquals("tenant-demo", eventSet.trustedContext().tenantId());
    assertEquals("engagement-2", eventSet.trustedContext().engagementId());
    assertEquals(
        List.of("evt-asset", "evt-action-a", "evt-action-b", "evt-action-c", "evt-bg-new",
            "evt-bg-incon"),
        eventSet.eventIds());

    RunnerManifest manifest = loader.loadManifest(fixture("manifest.json"), eventSet);
    assertEquals(3, manifest.actionBudget());
    assertEquals(
        List.of("T-A", "T-B", "T-C"),
        manifest.candidates().stream().map(RunnerManifest.RunnerCandidate::candidateId).toList());
    assertEquals(2, manifest.background().size());
  }

  @Test
  void unknownPayloadFieldRejectsThroughProductionCodec() {
    assertThrows(
        InvalidSourceEventException.class,
        () -> loader.loadEventSet(fixture("event-set-unknown-field.json")));
  }

  @Test
  void duplicateJsonKeyRejects() {
    assertThrows(
        InvalidFixtureException.class,
        () -> loader.loadEventSet(fixture("event-set-duplicate-key.json")));
  }

  @Test
  void blankTrustedContextRejects() {
    InvalidFixtureException error =
        assertThrows(
            InvalidFixtureException.class,
            () -> loader.loadEventSet(fixture("event-set-blank-tenant.json")));
    assertTrue(error.getMessage().contains("tenantId"), error.getMessage());
  }

  @Test
  void duplicateEventIdRejects() {
    InvalidFixtureException error =
        assertThrows(
            InvalidFixtureException.class,
            () -> loader.loadEventSet(fixture("event-set-duplicate-id.json")));
    assertTrue(error.getMessage().contains("duplicate source_event_id"), error.getMessage());
  }

  @Test
  void duplicateStreamPositionRejects() {
    InvalidFixtureException error =
        assertThrows(
            InvalidFixtureException.class,
            () -> loader.loadEventSet(fixture("event-set-duplicate-position.json")));
    assertTrue(error.getMessage().contains("duplicate stream position"), error.getMessage());
  }

  @Test
  void manifestMissingReferenceRejects() {
    FixtureEventSet eventSet = loader.loadEventSet(fixture("event-set.json"));
    InvalidFixtureException error =
        assertThrows(
            InvalidFixtureException.class,
            () -> loader.loadManifest(fixture("manifest-missing-reference.json"), eventSet));
    assertTrue(error.getMessage().contains("unknown source_event_id"), error.getMessage());
  }

  @Test
  void manifestEmptyActionRejects() {
    FixtureEventSet eventSet = loader.loadEventSet(fixture("event-set.json"));
    InvalidFixtureException error =
        assertThrows(
            InvalidFixtureException.class,
            () -> loader.loadManifest(fixture("manifest-empty-action.json"), eventSet));
    assertTrue(
        error.getMessage().contains("action_event_ids must be nonempty"), error.getMessage());
  }

  @Test
  void manifestDuplicateCandidateRejects() {
    FixtureEventSet eventSet = loader.loadEventSet(fixture("event-set.json"));
    InvalidFixtureException error =
        assertThrows(
            InvalidFixtureException.class,
            () -> loader.loadManifest(fixture("manifest-duplicate-candidate.json"), eventSet));
    assertTrue(error.getMessage().contains("duplicate candidate_id"), error.getMessage());
  }

  @Test
  void manifestUnknownCandidateRejects() {
    FixtureEventSet eventSet = loader.loadEventSet(fixture("event-set.json"));
    InvalidFixtureException error =
        assertThrows(
            InvalidFixtureException.class,
            () -> loader.loadManifest(fixture("manifest-unknown-candidate.json"), eventSet));
    assertTrue(error.getMessage().contains("must be exactly"), error.getMessage());
  }

  @Test
  void scenarioLabelInPayloadRejects() {
    InvalidFixtureException error =
        assertThrows(
            InvalidFixtureException.class,
            () -> loader.loadEventSet(eventSetWithBanner("F-STILL")));
    assertTrue(error.getMessage().contains("forbidden runner token F-STILL"), error.getMessage());
  }

  @Test
  void candidateTokenInPayloadRejects() {
    InvalidFixtureException error =
        assertThrows(
            InvalidFixtureException.class, () -> loader.loadEventSet(eventSetWithBanner("T-A")));
    assertTrue(error.getMessage().contains("forbidden runner token T-A"), error.getMessage());
  }

  @Test
  void reformattedInputPreservesTypedEquality() throws Exception {
    ObjectMapper mapper = Jackson.newObjectMapper();
    String original = fixture("event-set.json");
    String compact = mapper.writeValueAsString(mapper.readTree(original));

    FixtureEventSet fromOriginal = loader.loadEventSet(original);
    FixtureEventSet fromCompact = loader.loadEventSet(compact);

    assertEquals(sourceEvents(fromOriginal), sourceEvents(fromCompact));
    assertEquals(hashes(fromOriginal), hashes(fromCompact));

    RunnerManifest manifest = loader.loadManifest(fixture("manifest.json"), fromOriginal);
    assertEquals(
        PlannerCandidateSet.from(fromOriginal.trustedContext(), manifest),
        PlannerCandidateSet.from(fromCompact.trustedContext(), manifest));
  }

  private static List<?> sourceEvents(FixtureEventSet set) {
    return set.events().stream().map(CanonicalSourceEvent::event).toList();
  }

  private static List<String> hashes(FixtureEventSet set) {
    return set.events().stream().map(CanonicalSourceEvent::canonicalSourceHash).toList();
  }

  private static String eventSetWithBanner(String banner) {
    return """
        {
          "trusted_context": {"tenant_id": "tenant-demo", "engagement_id": "engagement-2"},
          "events": [
            {
              "source_event_id": "evt-asset",
              "event_type": "asset_observation",
              "source_stream_id": "stream-e2",
              "source_sequence": 1,
              "schema_version": 1,
              "occurred_at": "2026-06-01T10:00:00Z",
              "payload": {
                "cluster_id": "cluster-1",
                "resource_kind": "Deployment",
                "resource_uid": "uid-shared",
                "banner": "%s"
              }
            }
          ]
        }
        """
        .formatted(banner);
  }
}
