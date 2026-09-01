package org.stellar.anchor.platform.job;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.stellar.anchor.auth.NonceStore;
import org.stellar.anchor.util.Log;

@RequiredArgsConstructor
public class NonceCleanupJob {
  private final NonceStore nonceStore;

  // Runs every 15 minutes rather than hourly: the nonce table is now shared with SEP-10, whose
  // /auth traffic is expected to be far higher-volume than SEP-45's (the original, lower-volume
  // consumer this schedule was tuned for), so expired/used rows should be reaped more often.
  @Scheduled(fixedRate = 1000 * 60 * 15)
  public void cleanup() {
    Log.info("Cleaning up expired nonces");
    nonceStore.deleteExpiredNonces();
  }
}
