package org.stellar.anchor.platform.job;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.stellar.anchor.auth.NonceStore;
import org.stellar.anchor.util.Log;

@RequiredArgsConstructor
public class NonceCleanupJob {
  private final NonceStore nonceStore;

  @Scheduled(fixedRate = 1000 * 60 * 60) // 1 hour
  public void cleanup() {
    Log.info("Cleaning up expired nonces");
    nonceStore.deleteExpiredNonces();
  }
}
