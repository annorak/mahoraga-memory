package dev.mahoraga.memory.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.mahoraga.memory.contract.RelevantContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fixed golden vectors and equivalence rules for context fingerprint policy v1. */
class RelevantContextFingerprintTest {

  private static final String UNBOUND_GOLDEN_HASH =
      "09adc03dcc9e0dfa2cdf4a634ef1952a9b0dc476c712cdccb27fe2ea640d877f";
  private static final String BOUND_GOLDEN_HASH =
      "5a07a4de1c8d4dce70dfd561f00b876730ee74f52c4768d847237a9135106f32";

  @Test
  void unboundGoldenVectorIsStable() {
    assertEquals(UNBOUND_GOLDEN_HASH, RelevantContextFingerprint.hash(healthCheck(null, false)));
  }

  @Test
  void boundGoldenVectorIsStable() {
    assertEquals(
        BOUND_GOLDEN_HASH, RelevantContextFingerprint.hash(healthCheck("10.0.0.7", true)));
  }

  @Test
  void absentNullAndEmptyParametersAreOneFingerprint() {
    assertEquals(
        UNBOUND_GOLDEN_HASH,
        RelevantContextFingerprint.hash(context("/health", null, null, false)));
    assertEquals(
        UNBOUND_GOLDEN_HASH,
        RelevantContextFingerprint.hash(context("/health", Map.of(), null, false)));
  }

  @Test
  void parameterOrderDoesNotChangeTheFingerprint() {
    Map<String, Object> forward = new LinkedHashMap<>();
    forward.put("a", 1);
    forward.put("b", "two");
    Map<String, Object> reversed = new LinkedHashMap<>();
    reversed.put("b", "two");
    reversed.put("a", 1);

    assertEquals(
        RelevantContextFingerprint.hash(context("/login", forward, null, false)),
        RelevantContextFingerprint.hash(context("/login", reversed, null, false)));
  }

  @Test
  void numericallyEqualParameterFormsShareOneFingerprint() {
    assertEquals(
        RelevantContextFingerprint.hash(
            context("/login", Map.of("limit", new BigDecimal("10.0")), null, false)),
        RelevantContextFingerprint.hash(context("/login", Map.of("limit", 10), null, false)));
  }

  @Test
  void parameterTypeDistinctionsRemainDistinct() {
    assertNotEquals(
        RelevantContextFingerprint.hash(context("/login", Map.of("limit", "10"), null, false)),
        RelevantContextFingerprint.hash(context("/login", Map.of("limit", 10), null, false)));
  }

  @Test
  void unboundAddressIsExcludedEvenWhenRetainedAsEvidence() {
    assertEquals(
        UNBOUND_GOLDEN_HASH, RelevantContextFingerprint.hash(healthCheck("10.0.0.7", false)));
  }

  @Test
  void boundAddressChangeChangesTheFingerprint() {
    assertNotEquals(
        RelevantContextFingerprint.hash(healthCheck("10.0.0.7", true)),
        RelevantContextFingerprint.hash(healthCheck("10.0.0.8", true)));
  }

  @Test
  void domainStringsAreNotCaseFoldedOrTrimmed() {
    RelevantContext exact = context("/health", null, null, false);
    assertNotEquals(
        RelevantContextFingerprint.hash(exact),
        RelevantContextFingerprint.hash(
            new RelevantContext("HTTPS", 443, "/health", null, null, false)));
    assertNotEquals(
        RelevantContextFingerprint.hash(exact),
        RelevantContextFingerprint.hash(context("/health ", null, null, false)));
  }

  private static RelevantContext healthCheck(String targetAddress, boolean isAddressBound) {
    return context("/health", Map.of(), targetAddress, isAddressBound);
  }

  private static RelevantContext context(
      String route, Map<String, Object> parameters, String targetAddress, boolean isAddressBound) {
    return new RelevantContext("https", 443, route, parameters, targetAddress, isAddressBound);
  }
}
