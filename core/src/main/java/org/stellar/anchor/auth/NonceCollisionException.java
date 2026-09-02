package org.stellar.anchor.auth;

/** Thrown when a nonce id is already in use, so a new nonce could not be created for it. */
public class NonceCollisionException extends RuntimeException {
  public NonceCollisionException(String id) {
    super("Duplicate nonce id: " + id);
  }
}
