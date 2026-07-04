package dev.mahoraga.memory.contract;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical-encoding primitives owned by the source contract and shared by the
 * source-event canonicalizer and the relevant-context fingerprint. These rules
 * are part of canonicalization version 1; changing them changes recorded
 * hashes and requires a new schema version and golden vectors.
 */
public final class CanonicalEncoding {

  private CanonicalEncoding() {}

  /** Lowercase hex SHA-256, the one digest form used for canonical hashes. */
  public static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /** Lexically sorted parameter map with each value in canonical scalar form. */
  public static Map<String, Object> canonicalParameters(Map<String, Object> parameters) {
    Map<String, Object> canonical = new TreeMap<>();
    if (parameters != null) {
      for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
        canonical.put(parameter.getKey(), canonicalScalar(parameter.getValue()));
      }
    }
    return canonical;
  }

  /**
   * Numerically equal parameter values must share one canonical form, so
   * decimals are stripped and integral decimals become plain integers.
   * Handling only BigDecimal is complete because SourceEventValidator's scalar
   * whitelist admits no Double or Float; the two rules must evolve together.
   */
  public static Object canonicalScalar(Object value) {
    if (value instanceof BigDecimal decimal) {
      BigDecimal stripped = decimal.stripTrailingZeros();
      return stripped.scale() <= 0 ? stripped.toBigIntegerExact() : stripped;
    }
    return value;
  }
}
