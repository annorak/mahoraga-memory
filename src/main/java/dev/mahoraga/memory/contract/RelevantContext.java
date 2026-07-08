package dev.mahoraga.memory.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The stable logical check context shared by finding observations and test
 * attempts. Ephemeral Pod names, Pod UIDs, and IP addresses do not belong
 * here; an explicitly address-bound check carries its exact target address.
 * Mahoraga computes the context fingerprint during ingestion; the source never
 * supplies a hash.
 */
public record RelevantContext(
    @JsonProperty("protocol") @NotBlank String protocol,
    @JsonProperty("port") @NotNull @Min(1) @Max(65535) Integer port,
    @JsonProperty("normalized_route") @NotBlank String normalizedRoute,
    @JsonProperty("parameters") Map<String, Object> parameters,
    @JsonProperty("target_address") String targetAddress,
    @JsonProperty("is_address_bound") @NotNull Boolean isAddressBound) {

  public RelevantContext {
    if (parameters != null) {
      parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
  }
}
