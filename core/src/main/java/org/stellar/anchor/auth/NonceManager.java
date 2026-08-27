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
}
