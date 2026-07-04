package dev.mahoraga.memory.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * The recorded identity of one canonical asset. Minted once as a creation fact
 * when the authoritative key first appears; replay and restart read it back and
 * never mint a replacement.
 */
public record AssetId(UUID value) {

  public AssetId {
    Objects.requireNonNull(value, "value");
  }
}
