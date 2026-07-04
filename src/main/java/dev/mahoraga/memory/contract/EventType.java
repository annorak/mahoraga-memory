package dev.mahoraga.memory.contract;

import java.util.Optional;

/** Wire-level discriminator for the four schema-version-1 source-event types. */
public enum EventType {
  ASSET_OBSERVATION("asset_observation", SourcePayload.AssetObservation.class),
  FINDING_OBSERVATION("finding_observation", SourcePayload.FindingObservation.class),
  TEST_ATTEMPT("test_attempt", SourcePayload.TestAttempt.class),
  ENGAGEMENT_COMPLETED("engagement_completed", SourcePayload.EngagementCompleted.class);

  private final String wireValue;
  private final Class<? extends SourcePayload> payloadType;

  EventType(String wireValue, Class<? extends SourcePayload> payloadType) {
    this.wireValue = wireValue;
    this.payloadType = payloadType;
  }

  public String wireValue() {
    return wireValue;
  }

  Class<? extends SourcePayload> payloadType() {
    return payloadType;
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
