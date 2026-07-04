package dev.mahoraga.memory.identity;

import java.util.Objects;

/**
 * Outcome of recording one asset observation under resolution policy version 1.
 * The MVP persists exactly these two states; a weak match is never promoted to
 * a resolution and carries no canonical asset.
 */
public sealed interface AssetResolution {

  /** An authoritative Deployment key resolved to its recorded canonical asset. */
  record Resolved(AssetId assetId) implements AssetResolution {
    public Resolved {
      Objects.requireNonNull(assetId, "assetId");
    }
  }

  /** Weak signals collided with confirmed assets; held with no posture effect. */
  record Ambiguous() implements AssetResolution {}
}
