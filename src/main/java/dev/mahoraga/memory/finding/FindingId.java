package dev.mahoraga.memory.finding;

import java.util.Objects;
import java.util.UUID;

/**
 * The recorded identity of one finding. Minted once as a creation fact when the
 * match identity first appears; replay and restart read it back and never mint
 * a replacement. Never supplied by any source payload.
 */
public record FindingId(UUID value) {

  public FindingId {
    Objects.requireNonNull(value, "value");
  }
}
