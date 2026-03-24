package org.stellar.anchor.platform.fireblocks.job;

import static org.stellar.anchor.util.Log.debug;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.errorEx;
import static org.stellar.anchor.util.Log.info;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.stellar.anchor.api.custody.fireblocks.TransactionDetails;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.CustodyException;
import org.stellar.anchor.platform.config.FireblocksConfig;
import org.stellar.anchor.platform.custody.CustodyPayment;
import org.stellar.anchor.platform.custody.CustodyPaymentService;
import org.stellar.anchor.platform.custody.CustodyTransactionService;
import org.stellar.anchor.platform.custody.fireblocks.FireblocksEventService;
import org.stellar.anchor.platform.data.CustodyTransactionStatus;
import org.stellar.anchor.platform.data.JdbcCustodyTransaction;

public class FireblocksTransactionsReconciliationJob {

  private final FireblocksConfig fireblocksConfig;
  private final CustodyPaymentService<TransactionDetails> custodyPaymentService;
  private final FireblocksEventService fireblocksEventService;
  private final CustodyTransactionService custodyTransactionService;

  public FireblocksTransactionsReconciliationJob(
      FireblocksConfig fireblocksConfig,
      CustodyPaymentService<TransactionDetails> custodyPaymentService,
      FireblocksEventService fireblocksEventService,
      CustodyTransactionService custodyTransactionService) {
    this.fireblocksConfig = fireblocksConfig;
    this.custodyPaymentService = custodyPaymentService;
    this.fireblocksEventService = fireblocksEventService;
    this.custodyTransactionService = custodyTransactionService;
  }

  @Scheduled(cron = "${custody.fireblocks.reconciliation.cron_expression}")
  public void reconcileTransactions() {
    info("Fireblocks Transaction Reconciliation job started");

    custodyTransactionService
        .getOutboundTransactionsEligibleForReconciliation()
        .forEach(this::reconcileOutboundTransactions);

    List<JdbcCustodyTransaction> inboundTransactions =
        custodyTransactionService.getInboundTransactionsEligibleForReconciliation();
    reconcileInboundTransactions(inboundTransactions);

    info("Fireblocks Transaction Reconciliation job finished");
  }

  private void reconcileOutboundTransactions(JdbcCustodyTransaction txn) {
    try {
      TransactionDetails fireblocksTxn =
          custodyPaymentService.getTransactionById(txn.getExternalTxId());

      int attempt = txn.getReconciliationAttemptCount() + 1;
      if (fireblocksTxn.getStatus().isObservable()) {
        handleStatusChanged(fireblocksTxn, attempt);
      } else {
        handleStatusNotChanged(txn, attempt, fireblocksTxn.getExternalTxId());
      }
    } catch (AnchorException | IOException e) {
      errorEx(String.format("Failed to reconcile status for transaction (id=%s)", txn.getId()), e);
    }
  }

  private void reconcileInboundTransactions(List<JdbcCustodyTransaction> transactions) {
    if (transactions.isEmpty()) {
      debug("No inbound transactions to reconcile");
      return;
    }

    Instant startTime =
        transactions.stream()
            .map(JdbcCustodyTransaction::getCreatedAt)
            .min(Instant::compareTo)
            .orElse(null);

    try {
      List<TransactionDetails> fireblocksTransactions =
          custodyPaymentService.getTransactionsByTimeRange(startTime, Instant.now());

      if (fireblocksTransactions.isEmpty()) {
        debug("No Fireblocks transactions within specified time range");
        return;
      }

      Map<String, TransactionDetails> mappings =
          fireblocksTransactions.stream()
              .filter(
                  txn ->
                      !StringUtils.isEmpty(txn.getDestinationAddress())
                          && !StringUtils.isEmpty(txn.getDestinationTag()))
              .collect(
                  Collectors.toMap(
                      txn ->
                          txn.getDestinationAddress() + StringUtils.SPACE + txn.getDestinationTag(),
                      txn -> txn,
                      (txn1, txn2) -> txn1));

      transactions.forEach(
          txn -> {
            int attempt = txn.getReconciliationAttemptCount() + 1;

            try {
              String key = txn.getToAccount() + StringUtils.SPACE + txn.getMemo();
              TransactionDetails fireblocksTxn = mappings.get(key);
              if (fireblocksTxn != null && fireblocksTxn.getStatus().isObservable()) {
                handleStatusChanged(fireblocksTxn, attempt);
              } else {
                final String externalTxId =
                    fireblocksTxn != null ? fireblocksTxn.getExternalTxId() : null;
                handleStatusNotChanged(txn, attempt, externalTxId);
              }
            } catch (AnchorException | IOException e) {
              errorEx(
                  String.format("Failed to reconcile status for transaction (id=%s)", txn.getId()),
                  e);
            }
          });
    } catch (CustodyException e) {
      errorEx("Failed to retrieve fireblocks transactions", e);
    }
  }

  private void handleStatusChanged(TransactionDetails fireblocksTxn, int attempt)
      throws IOException, AnchorException {
    debugF(
        "Reconciliation attempt #[{}]: Fireblocks transaction status changed to [{}]",
        attempt,
        fireblocksTxn.getStatus().name());
    Optional<CustodyPayment> payment = fireblocksEventService.convert(fireblocksTxn);
    if (payment.isPresent()) {
      fireblocksEventService.handlePayment(payment.get());
    }
  }

  private void handleStatusNotChanged(
      JdbcCustodyTransaction txn, int attempt, String externalTxnId) {
    debugF("Reconciliation attempt #[{}]: Fireblocks transaction status wasn't changed", attempt);

    txn.setReconciliationAttemptCount(attempt);
    if (txn.getReconciliationAttemptCount()
        >= fireblocksConfig.getReconciliation().getMaxAttempts()) {
      debugF("Change transaction [{}] status to FAILED", txn.getId());
      txn.setStatus(CustodyTransactionStatus.FAILED.toString());
    }

    if (!StringUtils.isEmpty(externalTxnId)) {
      txn.setExternalTxId(externalTxnId);
    }
    custodyTransactionService.updateCustodyTransaction(txn);
  }
}
