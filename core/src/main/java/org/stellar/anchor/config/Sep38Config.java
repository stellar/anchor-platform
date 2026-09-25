package org.stellar.anchor.config;

@SuppressWarnings("SameReturnValue")
public interface Sep38Config {
  boolean isEnabled();

  boolean isAuthEnforced();

  /**
   * The maximum number of seconds into the future a client's requested {@code expire_after} may
   * push a quote's {@code expires_at}. A firm-price quote is a free option on the anchor's book;
   * this bounds how long that option can stay open. Requests beyond this window are rejected rather
   * than silently honored or clamped.
   *
   * @return the maximum quote expiration, in seconds.
   */
  Integer getMaxQuoteExpirationSeconds();
}
