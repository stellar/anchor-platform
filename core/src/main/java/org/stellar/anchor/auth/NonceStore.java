package org.stellar.anchor.auth;

import java.time.Instant;

public interface NonceStore {
  Nonce newInstance();

  Nonce findById(String id);

  Nonce save(Nonce nonce);

  void deleteExpiredNonces();

  int markAsUsed(String id, Instant now);
}
