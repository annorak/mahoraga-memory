package dev.mahoraga.memory.contract;

import java.util.Optional;

/** Wire-level discriminator for the four schema-version-1 source-event types. */
public enum EventType {
  ASSET_OBSERVATION("asset_observation"),
  FINDING_OBSERVATION("finding_observation"),
  TEST_ATTEMPT("test_attempt"),
  ENGAGEMENT_COMPLETED("engagement_completed");

  private final String wireValue;

  EventType(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  public static Optional<EventType> fromWire(String value) {
    for (EventType type : values()) {
      if (type.wireValue.equals(value)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }
}
