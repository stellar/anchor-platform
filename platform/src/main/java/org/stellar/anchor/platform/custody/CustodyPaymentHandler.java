package org.stellar.anchor.platform.custody;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.warnF;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.formatAmount;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.platform.data.CustodyTransactionStatus;
import org.stellar.anchor.platform.data.JdbcCustodyTransaction;
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo;

/**
 * Abstract custody payment handler. Contains common logic for payment validation, custody and SEP
 * transaction update
 */
public abstract class CustodyPaymentHandler {

  public static final String STELLAR_ASSET_PREFIX = "stellar:";
  private static final Set<String> SUPPORTED_ASSET_TYPES =
      Set.of("credit_alphanum4", "credit_alphanum12", "native");

  private final JdbcCustodyTransactionRepo custodyTransactionRepo;

  public CustodyPaymentHandler(JdbcCustodyTransactionRepo custodyTransactionRepo) {
    this.custodyTransactionRepo = custodyTransactionRepo;
  }

  /**
   * Handle inbound(withdrawal) payment
   *
   * @param txn custody transaction
   * @param payment inbound custody payment
   * @throws AnchorException if error happens during SEP transaction update
   * @throws IOException if error happens during SEP transaction update
   */
  public abstract void onReceived(JdbcCustodyTransaction txn, CustodyPayment payment)
      throws AnchorException, IOException;

  /**
   * Handle outbound(deposit) payment
   *
   * @param txn custody transaction
   * @param payment outbound custody payment
   * @throws AnchorException if error happens during SEP transaction update
   * @throws IOException if error happens during SEP transaction update
   */
  public abstract void onSent(JdbcCustodyTransaction txn, CustodyPayment payment)
      throws AnchorException, IOException;

  protected void updateTransaction(JdbcCustodyTransaction txn, CustodyPayment payment) {
    txn.setUpdatedAt(payment.getUpdatedAt());
    txn.setFromAccount(payment.getFrom());
    txn.setExternalTxId(payment.getExternalTxId());
    txn.setStatus(getCustodyTransactionStatus(payment.getStatus()).toString());
    custodyTransactionRepo.save(txn);
  }

  protected void validatePayment(JdbcCustodyTransaction txn, CustodyPayment payment) {
    if (CustodyPayment.CustodyPaymentStatus.SUCCESS != payment.getStatus()) {
      return;
    }

    if (!SUPPORTED_ASSET_TYPES.contains(payment.getAssetType())) {
      debugF(
          "Unsupported asset type[{}]. Payment: id[{}], externalTxId[{}]",
          payment.getAssetType(),
          payment.getId(),
          payment.getExternalTxId());
    }

    String paymentAssetName = STELLAR_ASSET_PREFIX + payment.getAssetName();
    if (!txn.getAsset().equals(paymentAssetName)) {
      warnF(
          "Incoming payment asset[{}] does not match the expected asset[{}]. Payment: id[{}], "
              + "externalTxId[{}] ",
          payment.getAssetName(),
          txn.getAsset(),
          payment.getId(),
          payment.getExternalTxId());
    }

    // Check if the payment contains the expected amount (or greater)
    BigDecimal expectedAmount = decimal(txn.getAmount());
    BigDecimal gotAmount = decimal(payment.getAmount());

    if (gotAmount.compareTo(expectedAmount) < 0) {
      warnF(
          "The incoming payment amount was insufficient. Expected: [{}], Received: [{}]. Payment: id[{}], externalTxId[{}]",
          formatAmount(expectedAmount),
          formatAmount(gotAmount),
          payment.getId(),
          payment.getExternalTxId());
    }
  }

  protected CustodyTransactionStatus getCustodyTransactionStatus(
      CustodyPayment.CustodyPaymentStatus custodyPaymentStatus) {
    switch (custodyPaymentStatus) {
      case SUCCESS:
        return CustodyTransactionStatus.COMPLETED;
      case ERROR:
        return CustodyTransactionStatus.FAILED;
      default:
        throw new RuntimeException(
            String.format("Unsupported custody transaction status[%s]", custodyPaymentStatus));
    }
  }
}
