package org.stellar.anchor.auth;

import java.time.Instant;

public interface NonceStore {
  Nonce newInstance();

  Nonce findById(String id);

  Nonce save(Nonce nonce);

  /**
   * Atomically insert a nonce only if its id doesn't already exist. Unlike {@link
   * #findById(String)} followed by {@link #save(Nonce)}, this is a single atomic database operation
   * with no check-then-act race between concurrent callers.
   *
   * @param nonce the nonce to insert
   * @return true if the nonce was inserted, false if an id conflict already existed
   */
  boolean insertIfAbsent(Nonce nonce);

  void deleteExpiredNonces();

  int markAsUsed(String id, Instant now);
}
