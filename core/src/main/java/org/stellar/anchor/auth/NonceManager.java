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
   * Mark a nonce as used. If the nonce is already used, an exception is thrown.
   *
   * @param id the nonce id
   */
  public void use(String id) {
    Nonce nonce = nonceStore.findById(id);
    if (nonce.getUsed()) {
      throw new RuntimeException("Nonce already used");
    }

    nonce.setUsed(true);
    nonceStore.save(nonce);
  }

  /**
   * Verify a nonce. A nonce is considered valid if it exists, is unused, and has not expired.
   *
   * @param id the nonce id
   * @return true if the nonce is valid
   */
  public boolean verify(String id) {
    Nonce nonce = nonceStore.findById(id);

    return nonce != null && !nonce.getUsed() && nonce.getExpiresAt().isAfter(clock.instant());
  }
}
