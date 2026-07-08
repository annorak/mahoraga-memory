package dev.mahoraga.memory.finding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mahoraga.memory.contract.CanonicalEncoding;
import dev.mahoraga.memory.contract.RelevantContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context fingerprint policy version 1: lowercase SHA-256 over compact UTF-8
 * canonical JSON in the fixed order {@code protocol, port, normalized_route,
 * parameters, is_address_bound, target_address?}. Parameters sort lexically
 * with the source contract's scalar rules; absent parameters mean {@code {}}.
 * The target address appears only for an explicitly address-bound check.
 * Validated domain strings are kept exactly as supplied and are not trimmed or
 * case-folded here.
 */
public final class RelevantContextFingerprint {

  private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

  private RelevantContextFingerprint() {}

  public static String hash(RelevantContext context) {
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("protocol", context.protocol());
    canonical.put("port", context.port());
    canonical.put("normalized_route", context.normalizedRoute());
    canonical.put("parameters", CanonicalEncoding.canonicalParameters(context.parameters()));
    canonical.put("is_address_bound", context.isAddressBound());
    if (context.isAddressBound()) {
      canonical.put("target_address", context.targetAddress());
    }
    try {
      return CanonicalEncoding.sha256Hex(CANONICAL_MAPPER.writeValueAsBytes(canonical));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("validated relevant context failed to serialize", e);
    }
  }
}
