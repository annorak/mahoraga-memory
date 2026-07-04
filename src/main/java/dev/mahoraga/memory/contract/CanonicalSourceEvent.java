package dev.mahoraga.memory.contract;

/**
 * A validated source event with its canonical bytes, server-computed
 * lowercase-hex SHA-256 hash, and the canonical payload subtree that storage
 * persists verbatim, so the hashed and stored payload share one serialization.
 * The canonical bytes are defensively copied in both directions so no caller
 * can mutate shared state.
 */
public record CanonicalSourceEvent(
    SourceEvent event,
    byte[] canonicalJson,
    String canonicalSourceHash,
    String canonicalPayloadJson) {

  public CanonicalSourceEvent {
    canonicalJson = canonicalJson.clone();
  }

  @Override
  public byte[] canonicalJson() {
    return canonicalJson.clone();
  }
}
