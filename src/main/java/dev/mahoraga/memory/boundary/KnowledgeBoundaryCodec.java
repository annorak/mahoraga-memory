package dev.mahoraga.memory.boundary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalEncoding;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boundary canonicalization version 1: compact UTF-8 JSON of the shape
 * {@code {"positions":[{"source_stream_id":...,"last_data_sequence":...}]}}
 * in the boundary's sorted stream order, digested as lowercase SHA-256.
 * Changing this shape or order is an explicit codec-version decision; it is
 * deliberately independent of the source-event schema version.
 */
public final class KnowledgeBoundaryCodec {

  private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

  private KnowledgeBoundaryCodec() {}

  public static String canonicalJson(KnowledgeBoundary boundary) {
    List<Map<String, Object>> positions =
        boundary.positions().stream().map(KnowledgeBoundaryCodec::canonicalPosition).toList();
    try {
      return CANONICAL_MAPPER.writeValueAsString(Map.of("positions", positions));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("validated knowledge boundary failed to serialize", e);
    }
  }

  public static String hash(KnowledgeBoundary boundary) {
    return CanonicalEncoding.sha256Hex(canonicalJson(boundary).getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> canonicalPosition(BoundaryPosition position) {
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("source_stream_id", position.sourceStreamId());
    canonical.put("last_data_sequence", position.lastDataSequence());
    return canonical;
  }
}
