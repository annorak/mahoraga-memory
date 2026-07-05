package dev.mahoraga.memory.fixture;

import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.TrustedContext;
import java.util.List;
import java.util.Objects;

/**
 * One trusted-context-scoped dataset of ordered source events, each already
 * decoded, validated, canonicalized, and hashed by the production
 * {@link dev.mahoraga.memory.contract.SourceEventCodec}. Trusted tenant and
 * engagement identity live here, never inside a payload, so the fixture runner
 * supplies them exactly as a future source adapter would. The event list is
 * held in arrival order for deterministic downstream ingestion.
 */
public record FixtureEventSet(TrustedContext trustedContext, List<CanonicalSourceEvent> events) {

  public FixtureEventSet {
    Objects.requireNonNull(trustedContext, "trustedContext");
    events = List.copyOf(events);
  }

  /** Source-event IDs in arrival order; used to validate manifest references. */
  public List<String> eventIds() {
    return events.stream().map(canonical -> canonical.event().sourceEventId()).toList();
  }
}
