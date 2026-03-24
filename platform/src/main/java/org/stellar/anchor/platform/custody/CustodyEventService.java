package org.stellar.anchor.platform.custody;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.RECEIVE;
import static org.stellar.anchor.util.Log.warnF;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.platform.data.JdbcCustodyTransaction;
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo;

/**
 * Basic class, that contains common logic to handle custody event. It links event to custody
 * transaction and pass event to appropriate handler. Event can be retrieved as webhook or as a
 * result of reconciliation job
 */
public abstract class CustodyEventService {

  private final JdbcCustodyTransactionRepo custodyTransactionRepo;
  private final Sep6CustodyPaymentHandler sep6CustodyPaymentHandler;
  private final Sep24CustodyPaymentHandler sep24CustodyPaymentHandler;
  private final Sep31CustodyPaymentHandler sep31CustodyPaymentHandler;

  protected CustodyEventService(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      Sep6CustodyPaymentHandler sep6CustodyPaymentHandler,
      Sep24CustodyPaymentHandler sep24CustodyPaymentHandler,
      Sep31CustodyPaymentHandler sep31CustodyPaymentHandler) {
    this.custodyTransactionRepo = custodyTransactionRepo;
    this.sep6CustodyPaymentHandler = sep6CustodyPaymentHandler;
    this.sep24CustodyPaymentHandler = sep24CustodyPaymentHandler;
    this.sep31CustodyPaymentHandler = sep31CustodyPaymentHandler;
  }

  public abstract void handleEvent(String event, Map<String, String> headers)
      throws BadRequestException;

  public void handlePayment(CustodyPayment payment) throws AnchorException, IOException {
    JdbcCustodyTransaction custodyTransaction = getCustodyTransaction(payment);

    if (custodyTransaction == null) {
      warnF(
          "Custody transaction was not found. Payment: id[{}], externalTxId[{}]",
          payment.getId(),
          payment.getExternalTxId());
      return;
    }

    switch (Sep.from(custodyTransaction.getProtocol())) {
      case SEP_6:
        switch (Kind.from(custodyTransaction.getKind())) {
          case DEPOSIT:
          case DEPOSIT_EXCHANGE:
            sep6CustodyPaymentHandler.onSent(custodyTransaction, payment);
            return;
          case WITHDRAWAL:
          case WITHDRAWAL_EXCHANGE:
            sep6CustodyPaymentHandler.onReceived(custodyTransaction, payment);
            return;
        }
      case SEP_24:
        switch (Kind.from(custodyTransaction.getKind())) {
          case DEPOSIT:
            sep24CustodyPaymentHandler.onSent(custodyTransaction, payment);
            return;
          case WITHDRAWAL:
            sep24CustodyPaymentHandler.onReceived(custodyTransaction, payment);
            return;
        }
        break;
      case SEP_31:
        if (RECEIVE == Kind.from(custodyTransaction.getKind())) {
          sep31CustodyPaymentHandler.onReceived(custodyTransaction, payment);
          return;
        }
        break;
    }

    warnF(
        "Handler for custody transaction event with protocol[{}] and kind[{}] is not found. Payment: id[{}], externalTxId[{}]",
        custodyTransaction.getProtocol(),
        custodyTransaction.getKind(),
        payment.getId(),
        payment.getExternalTxId());
  }

  private JdbcCustodyTransaction getCustodyTransaction(CustodyPayment custodyPayment) {
    JdbcCustodyTransaction custodyTransaction =
        custodyTransactionRepo.findByExternalTxId(custodyPayment.getExternalTxId());

    if (custodyTransaction == null) {
      custodyTransaction =
          custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(
              custodyPayment.getTo(), custodyPayment.getTransactionMemo());
    }

    return custodyTransaction;
  }

  public void setExternalTxId(String to, String memo, String externalTxId) {
    JdbcCustodyTransaction custodyTransaction =
        custodyTransactionRepo.findFirstByToAccountAndMemoOrderByCreatedAtDesc(to, memo);

    if (custodyTransaction != null && StringUtils.isEmpty(custodyTransaction.getExternalTxId())) {
      custodyTransaction.setExternalTxId(externalTxId);
      custodyTransaction.setUpdatedAt(Instant.now());
      custodyTransactionRepo.save(custodyTransaction);
    }
  }
}
