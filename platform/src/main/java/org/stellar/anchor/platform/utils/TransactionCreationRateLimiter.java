package org.stellar.anchor.platform.utils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.stellar.anchor.api.exception.SepRateLimitExceededException;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.config.RateLimitConfig;

/**
 * Caps how many transaction-creation requests a single SEP-10 identity can make within a fixed time
 * window. State is in-memory and per-instance: in a horizontally-scaled deployment, each instance
 * enforces the limit independently.
 */
public class TransactionCreationRateLimiter {
  private static final int MAX_TRACKED_IDENTITIES = 100_000;

  private final RateLimitConfig config;
  private final Cache<String, AtomicInteger> requestCounts;

  public TransactionCreationRateLimiter(RateLimitConfig config) {
    this.config = config;
    this.requestCounts =
        CacheBuilder.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(Math.max(config.getWindowSeconds(), 1)))
            .maximumSize(MAX_TRACKED_IDENTITIES)
            .build();
  }

  public void checkAndRecord(WebAuthJwt token) throws SepRateLimitExceededException {
    if (!config.isEnabled() || token == null) {
      return;
    }

    String identity = token.getOwnerAccount() + ":" + token.getOwnerMemo();
    AtomicInteger count;
    try {
      count = requestCounts.get(identity, AtomicInteger::new);
    } catch (ExecutionException e) {
      // AtomicInteger::new never throws.
      throw new IllegalStateException(e);
    }

    if (count.incrementAndGet() > config.getMaxTransactionsPerWindow()) {
      throw new SepRateLimitExceededException(
          "Too many transaction-creation requests. Please try again later.");
    }
  }
}
