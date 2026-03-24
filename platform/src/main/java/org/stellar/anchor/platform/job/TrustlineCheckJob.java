package org.stellar.anchor.platform.job;

import static org.stellar.anchor.util.Log.info;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.rpc.method.NotifyTrustSetRequest;
import org.stellar.anchor.ledger.LedgerClient;
import org.stellar.anchor.platform.config.PropertyCustodyConfig;
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrust;
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrustRepo;
import org.stellar.anchor.platform.rpc.NotifyTrustSetHandler;
import org.stellar.sdk.exception.NetworkException;

public class TrustlineCheckJob {

  private final LedgerClient horizon;
  private final JdbcTransactionPendingTrustRepo transactionPendingTrustRepo;
  private final PropertyCustodyConfig custodyConfig;
  private final NotifyTrustSetHandler notifyTrustSetHandler;

  public TrustlineCheckJob(
      LedgerClient ledgerClient,
      JdbcTransactionPendingTrustRepo transactionPendingTrustRepo,
      PropertyCustodyConfig custodyConfig,
      NotifyTrustSetHandler notifyTrustSetHandler) {
    this.horizon = ledgerClient;
    this.transactionPendingTrustRepo = transactionPendingTrustRepo;
    this.custodyConfig = custodyConfig;
    this.notifyTrustSetHandler = notifyTrustSetHandler;
  }

  @Scheduled(cron = "${custody.trustline.check_cron_expression}")
  public void checkTrust() throws AnchorException {
    info("Trustline Check job started");

    for (JdbcTransactionPendingTrust t : transactionPendingTrustRepo.findAll()) {
      if (isCheckTimedOut(t)) {
        notifyTrustSetHandler.handle(
            NotifyTrustSetRequest.builder()
                .transactionId(t.getId())
                .message(custodyConfig.getTrustline().getTimeoutMessage())
                .success(false)
                .build());

        transactionPendingTrustRepo.delete(t);
      } else {
        boolean trustlineConfigured;
        try {
          trustlineConfigured = horizon.hasTrustline(t.getAccount(), t.getAsset());
        } catch (NetworkException ex) {
          trustlineConfigured = false;
        }

        if (trustlineConfigured) {
          notifyTrustSetHandler.handle(
              NotifyTrustSetRequest.builder().transactionId(t.getId()).success(true).build());

          transactionPendingTrustRepo.delete(t);
        }
      }
    }

    info("Trustline Check job finished");
  }

  private boolean isCheckTimedOut(JdbcTransactionPendingTrust trust) {
    return trust
        .getCreatedAt()
        .plus(custodyConfig.getTrustline().getCheckDuration(), ChronoUnit.MINUTES)
        .isBefore(Instant.now());
  }
}
