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
    String id = UUID.randomUUID().toString();
    if (nonceStore.findById(id) != null) {
      throw new RuntimeException("Duplicate nonce id");
    }

    Nonce nonce =
        new NonceBuilder(nonceStore)
            .id(id)
            .used(false)
            .expiresAt(clock.instant().plus(Duration.ofSeconds(expiresIn)))
            .build();

    return nonceStore.save(nonce);
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
