package org.stellar.anchor.auth;

import java.time.Instant;

public class NonceBuilder {
  final Nonce nonce;

  public NonceBuilder(NonceStore factory) {
    nonce = factory.newInstance();
  }

  public NonceBuilder id(String id) {
    nonce.setId(id);
    return this;
  }

  public NonceBuilder used(Boolean used) {
    nonce.setUsed(used);
    return this;
  }

  public NonceBuilder expiresAt(Instant expiresAt) {
    nonce.setExpiresAt(expiresAt);
    return this;
  }

  public Nonce build() {
    return nonce;
  }
}
