package org.stellar.anchor.config;

/**
 * Configuration for rate limiting transaction-creation requests (SEP-6/24/31) per authenticated
 * SEP-10 identity.
 */
public interface RateLimitConfig {
  boolean isEnabled();

  int getMaxTransactionsPerWindow();

  long getWindowSeconds();
}
