package org.stellar.anchor.auth;

import java.time.Instant;

/** Nonce is a one-time-use token that is used to authenticate a user. */
public interface Nonce {

  /**
   * A unique value for the nonce.
   *
   * @return the unique value
   */
  String getId();

  void setId(String id);

  /**
   * Whether this nonce has been used.
   *
   * @return true if the nonce has been used
   */
  Boolean getUsed();

  void setUsed(Boolean used);

  /**
   * The time at which this nonce expires.
   *
   * @return the expiration time
   */
  Instant getExpiresAt();

  void setExpiresAt(Instant expiresAt);
}
