package dev.mahoraga.memory.contract;

/**
 * A validated source event with its canonical bytes and server-computed
 * lowercase-hex SHA-256 hash. The canonical bytes are defensively copied in
 * both directions so no caller can mutate shared state.
 */
public record CanonicalSourceEvent(
    SourceEvent event, byte[] canonicalJson, String canonicalSourceHash) {

  public CanonicalSourceEvent {
    canonicalJson = canonicalJson.clone();
  }

  @Override
  public byte[] canonicalJson() {
    return canonicalJson.clone();
  }
}
