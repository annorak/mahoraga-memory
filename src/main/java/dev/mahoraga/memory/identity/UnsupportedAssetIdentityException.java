package dev.mahoraga.memory.identity;

/**
 * A UID-less observation matched no confirmed weak candidate. Provisional
 * asset creation is not part of resolution policy version 1.
 * The whole source event is rejected and rolled back. Messages carry stable
 * identifiers only, never observation evidence values.
 */
public final class UnsupportedAssetIdentityException extends RuntimeException {

  public UnsupportedAssetIdentityException(String message) {
    super(message);
  }
}
