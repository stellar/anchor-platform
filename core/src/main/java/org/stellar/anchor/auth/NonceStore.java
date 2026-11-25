package org.stellar.anchor.auth;

import java.time.Instant;

public interface NonceStore {
  Nonce newInstance();

  Nonce findById(String id);

  Nonce save(Nonce nonce);

  /**
   * Atomically insert a nonce only if its id doesn't already exist, using the nonce's own {@link
   * Nonce#getUsed()} value for the inserted row (so callers can pre-register an unused nonce, or
   * atomically claim an id as already-used in one step). Unlike {@link #findById(String)} followed
   * by {@link #save(Nonce)}, this is a single atomic database operation with no check-then-act race
   * between concurrent callers.
   *
   * @param nonce the nonce to insert
   * @return true if the nonce was inserted, false if an id conflict already existed
   */
  boolean insertIfAbsent(Nonce nonce);

  /**
   * Delete every nonce whose expiry is strictly before {@code now}. The caller supplies the instant
   * (from a {@link java.time.Clock}) so the comparison never depends on the database session's time
   * zone.
   *
   * @param now the instant to compare each nonce's expiry against
   */
  void deleteExpiredNonces(Instant now);

  int markAsUsed(String id, Instant now);
}
