package org.stellar.anchor.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/**
 * Manages nonces for web authentication requests. A nonce is a one-time-use token that expires
 * after a certain amount of time.
 */
@RequiredArgsConstructor
public class NonceManager {
  private static final SecureRandom secureRandom = new SecureRandom();

  private final NonceStore nonceStore;
  private final Clock clock;

  /**
   * Create a new nonce that expires in expiresIn seconds. An expired nonce is considered invalid
   * even if it is unused.
   *
   * @param expiresIn the number of seconds until the nonce expires
   * @return the nonce
   */
  public Nonce create(int expiresIn) {
    return createWithId(UUID.randomUUID().toString(), expiresIn);
  }

  /**
   * Create a new nonce with a caller-supplied id that expires in expiresIn seconds. Use this when
   * the id must be derivable by both the issuer and the verifier of the nonce, e.g. a SEP-10
   * challenge transaction hash, instead of an opaque random id.
   *
   * @param id the nonce id
   * @param expiresIn the number of seconds until the nonce expires
   * @return the nonce
   */
  public Nonce createWithId(String id, int expiresIn) {
    Nonce nonce =
        new NonceBuilder(nonceStore)
            .id(id)
            .used(false)
            .expiresAt(clock.instant().plus(Duration.ofSeconds(expiresIn)))
            .build();

    // A single atomic insert-if-absent, rather than findById() followed by save(): the latter is
    // a check-then-act race between concurrent callers, since JdbcNonce's assigned (non-generated)
    // id means Spring Data's save() can merge/overwrite an existing row instead of failing.
    if (!nonceStore.insertIfAbsent(nonce)) {
      throw new NonceCollisionException(id);
    }

    return nonce;
  }

  /**
   * Atomically verify and consume a nonce. Returns true if the nonce was valid (exists, unused, not
   * expired) and has been marked as used.
   *
   * @param id the nonce id
   * @return true if the nonce was successfully verified and consumed
   */
  public boolean verifyAndUse(String id) {
    return nonceStore.markAsUsed(id, clock.instant()) == 1;
  }

  /**
   * Atomically claim an id for first-time use, without requiring it to have been pre-registered by
   * {@link #create(int)}/{@link #createWithId(String, int)}. Use this when the id is independently
   * derivable by both the issuer and the verifier (e.g. a SEP-10 challenge transaction hash) and
   * doesn't need to be reserved ahead of time: the first caller to claim a given id wins (the row
   * is inserted already marked used), and every later claim of the same id fails.
   *
   * <p>{@code minRetentionSeconds} is measured from now (the moment of this claim), not from any
   * property of the id itself (e.g. a challenge's own signed expiry) -- anchoring retention to the
   * underlying resource's own deadline is tempting but fragile: a second, slower concurrent claim
   * attempt for the same id can still be validating (ledger lookups, signing, etc.) after that
   * deadline has passed, and if cleanup removes the row in that gap, the slower attempt succeeds in
   * claiming it too. Pass a value generous enough to outlast any realistic in-flight validation for
   * this id, not just the id's own nominal expiry.
   *
   * @param id the id to claim
   * @param minRetentionSeconds how long, at minimum, the claimed row must be retained before
   *     cleanup may remove it, counted from now
   * @return true if this call claimed the id for the first time, false if it was already claimed
   */
  public boolean claim(String id, int minRetentionSeconds) {
    Nonce nonce =
        new NonceBuilder(nonceStore)
            .id(id)
            .used(true)
            .expiresAt(clock.instant().plusSeconds(minRetentionSeconds))
            .build();

    return nonceStore.insertIfAbsent(nonce);
  }
}
