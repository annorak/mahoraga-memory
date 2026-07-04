package dev.mahoraga.memory.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * The single authoritative path from external source-event JSON to a
 * validated, canonicalized, hashed {@link CanonicalSourceEvent}.
 */
public final class SourceEventCodec {

  public static final int SUPPORTED_SCHEMA_VERSION = 1;
  public static final int MAX_SOURCE_EVENT_BYTES = 1_048_576;
  public static final int MAX_JSON_NESTING_DEPTH = 20;

  private final ObjectMapper strictMapper;
  private final SourceEventValidator validator;
  private final SourceEventCanonicalizer canonicalizer;

  @Inject
  public SourceEventCodec(ObjectMapper objectMapper, SourceEventValidator validator) {
    ObjectMapper managedMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.strictMapper = strictCopyOf(managedMapper);
    this.validator = Objects.requireNonNull(validator, "validator");
    this.canonicalizer = new SourceEventCanonicalizer(managedMapper);
  }

  public CanonicalSourceEvent decode(String json) {
    byte[] inputBytes = Objects.requireNonNull(json, "json").getBytes(StandardCharsets.UTF_8);
    if (inputBytes.length > MAX_SOURCE_EVENT_BYTES) {
      throw new InvalidSourceEventException(
          "source event exceeds " + MAX_SOURCE_EVENT_BYTES + " bytes");
    }
    SourceEvent event = toSourceEvent(parseEnvelope(inputBytes));
    validator.validate(event);
    byte[] canonicalJson = canonicalizer.canonicalBytes(event);
    return new CanonicalSourceEvent(event, canonicalJson, CanonicalEncoding.sha256Hex(canonicalJson));
  }

  private RawEnvelope parseEnvelope(byte[] inputBytes) {
    try {
      return strictMapper.readValue(inputBytes, RawEnvelope.class);
    } catch (IOException e) {
      throw new InvalidSourceEventException(
          "source event JSON is invalid: " + messageOf(e), e);
    }
  }

  private SourceEvent toSourceEvent(RawEnvelope raw) {
    String eventId = raw.sourceEventId();
    String eventTypeWire = requireField(raw.eventType(), "event_type", eventId);
    EventType eventType =
        EventType.fromWire(eventTypeWire)
            .orElseThrow(
                () ->
                    new InvalidSourceEventException(
                        fieldError(eventId, "event_type", "value is unsupported")));
    if (raw.schemaVersion() == null || raw.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
      throw new InvalidSourceEventException(
          fieldError(eventId, "schema_version", "must be " + SUPPORTED_SCHEMA_VERSION));
    }
    if (raw.sourceSequence() == null) {
      throw new InvalidSourceEventException(fieldError(eventId, "source_sequence", "is required"));
    }
    Instant occurredAt = parseOccurredAt(raw.occurredAt(), eventId);
    SourcePayload payload = bindPayload(eventType, raw.payload(), eventId);
    return new SourceEvent(
        eventId,
        eventType,
        raw.sourceStreamId(),
        raw.sourceSequence(),
        raw.schemaVersion(),
        occurredAt,
        payload);
  }

  private static Instant parseOccurredAt(String value, String eventId) {
    requireField(value, "occurred_at", eventId);
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new InvalidSourceEventException(
          fieldError(eventId, "occurred_at", "must be an ISO-8601 instant"), e);
    }
  }

  private SourcePayload bindPayload(EventType eventType, JsonNode payloadNode, String eventId) {
    if (payloadNode == null || payloadNode.isNull()) {
      throw new InvalidSourceEventException(fieldError(eventId, "payload", "is required"));
    }
    if (!payloadNode.isObject()) {
      throw new InvalidSourceEventException(
          fieldError(eventId, "payload", "must be a JSON object"));
    }
    Class<? extends SourcePayload> payloadType = eventType.payloadType();
    try {
      return strictMapper.treeToValue(payloadNode, payloadType);
    } catch (JsonProcessingException e) {
      throw new InvalidSourceEventException(
          fieldError(eventId, "payload", "is invalid: " + messageOf(e)), e);
    }
  }

  private static ObjectMapper strictCopyOf(ObjectMapper objectMapper) {
    ObjectMapper copy = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    copy.getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(MAX_JSON_NESTING_DEPTH).build());
    copy.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    copy.disable(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS);
    copy.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    copy.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    copy.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    copy.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    copy.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    copy.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    copy.coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    return copy;
  }

  private static String requireField(String value, String field, String eventId) {
    if (value == null || value.isBlank()) {
      throw new InvalidSourceEventException(fieldError(eventId, field, "is required"));
    }
    return value;
  }

  private static String fieldError(String eventId, String field, String problem) {
    String subject = eventId == null || eventId.isBlank() ? "source event" : "source event " + eventId;
    return subject + ": " + field + " " + problem;
  }

  private static String messageOf(IOException error) {
    String message =
        error instanceof JsonProcessingException jackson
            ? jackson.getOriginalMessage()
            : error.getMessage();
    if (error instanceof JsonMappingException mappingException
        && !mappingException.getPath().isEmpty()) {
      String field = mappingException.getPath().getLast().getFieldName();
      if (field != null) {
        return field + " " + message;
      }
    }
    return message;
  }

  private record RawEnvelope(
      @JsonProperty("source_event_id") String sourceEventId,
      @JsonProperty("event_type") String eventType,
      @JsonProperty("source_stream_id") String sourceStreamId,
      @JsonProperty("source_sequence") Long sourceSequence,
      @JsonProperty("schema_version") Integer schemaVersion,
      @JsonProperty("occurred_at") String occurredAt,
      @JsonProperty("payload") JsonNode payload) {}
}
