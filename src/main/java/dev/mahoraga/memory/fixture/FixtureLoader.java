package dev.mahoraga.memory.fixture;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalSourceEvent;
import dev.mahoraga.memory.contract.SourceEventCodec;
import dev.mahoraga.memory.contract.TrustedContext;
import java.util.List;
import java.util.Objects;

/**
 * Parses and validates a synthetic fixture bundle into immutable typed values.
 * Every source event is decoded through the production {@link SourceEventCodec}
 * rather than a permissive fixture mapper, and trusted context is read from the
 * dataset wrapper, never injected into a payload. The loader performs no
 * database work, holds no restart state, and returns equal values for equal
 * input, so repeated loads of the same bundle are interchangeable.
 */
public final class FixtureLoader {

  private final ObjectMapper strictMapper;
  private final SourceEventCodec codec;
  private final FixtureValidator validator;

  public FixtureLoader(ObjectMapper objectMapper, SourceEventCodec codec) {
    this.strictMapper = strictCopyOf(Objects.requireNonNull(objectMapper, "objectMapper"));
    this.codec = Objects.requireNonNull(codec, "codec");
    this.validator = new FixtureValidator();
  }

  /** Decodes the dataset wrapper and validates event identity, positions, and leakage. */
  public FixtureEventSet loadEventSet(String eventSetJson) {
    RawEventSet raw = read(eventSetJson, RawEventSet.class, "event set");
    FixtureEventSet eventSet =
        new FixtureEventSet(trustedContext(raw.context()), decode(raw.events()));
    validator.validateEventSet(eventSet);
    return eventSet;
  }

  /** Parses the runner manifest and validates its references against {@code eventSet}. */
  public RunnerManifest loadManifest(String manifestJson, FixtureEventSet eventSet) {
    Objects.requireNonNull(eventSet, "eventSet");
    RunnerManifest manifest = read(manifestJson, RunnerManifest.class, "manifest");
    validator.validateManifest(manifest, eventSet);
    return manifest;
  }

  private List<CanonicalSourceEvent> decode(List<JsonNode> events) {
    if (events == null) {
      throw new InvalidFixtureException("event set: events are required");
    }
    return events.stream().map(this::decodeOne).toList();
  }

  private CanonicalSourceEvent decodeOne(JsonNode event) {
    try {
      return codec.decode(strictMapper.writeValueAsString(event));
    } catch (JsonProcessingException e) {
      throw new InvalidFixtureException("event set: an event could not be reserialized", e);
    }
  }

  private static TrustedContext trustedContext(RawContext context) {
    if (context == null) {
      throw new InvalidFixtureException("event set: trusted_context is required");
    }
    try {
      return new TrustedContext(context.tenantId(), context.engagementId());
    } catch (IllegalArgumentException e) {
      throw new InvalidFixtureException("event set: " + e.getMessage(), e);
    }
  }

  private <T> T read(String json, Class<T> type, String subject) {
    Objects.requireNonNull(json, subject + " json");
    try {
      return strictMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new InvalidFixtureException(
          subject + ": JSON is invalid: " + e.getOriginalMessage(), e);
    }
  }

  private static ObjectMapper strictCopyOf(ObjectMapper objectMapper) {
    ObjectMapper copy = objectMapper.copy();
    copy.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    copy.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    copy.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    return copy;
  }

  private record RawEventSet(
      @JsonProperty("trusted_context") RawContext context,
      @JsonProperty("events") List<JsonNode> events) {}

  private record RawContext(
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("engagement_id") String engagementId) {}
}
