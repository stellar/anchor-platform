package org.stellar.anchor.platform.custody;

import static org.stellar.anchor.util.Log.infoF;

import java.io.IOException;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.config.RpcConfig;
import org.stellar.anchor.platform.data.CustodyTransactionStatus;
import org.stellar.anchor.platform.data.JdbcCustodyTransaction;
import org.stellar.anchor.platform.data.JdbcCustodyTransactionRepo;
import org.stellar.anchor.platform.service.AnchorMetrics;

/** Custody payment handler for SEP6 transactions */
public class Sep6CustodyPaymentHandler extends CustodyPaymentHandler {
  private final PlatformApiClient platformApiClient;
  private final RpcConfig rpcConfig;
  private final MetricsService metricsService;

  public Sep6CustodyPaymentHandler(
      JdbcCustodyTransactionRepo custodyTransactionRepo,
      PlatformApiClient platformApiClient,
      RpcConfig rpcConfig,
      MetricsService metricsService) {
    super(custodyTransactionRepo);
    this.platformApiClient = platformApiClient;
    this.rpcConfig = rpcConfig;
    this.metricsService = metricsService;
  }

  @Override
  public void onReceived(JdbcCustodyTransaction txn, CustodyPayment payment)
      throws AnchorException, IOException {
    infoF(
        "Incoming inbound payment for SEP-6 transaction. Payment: id[{}], externalTxId[{}], type[{}]",
        payment.getId(),
        payment.getExternalTxId(),
        txn.getType());

    validatePayment(txn, payment);
    updateTransaction(txn, payment);

    if (CustodyTransactionStatus.FAILED == CustodyTransactionStatus.from(txn.getStatus())) {
      platformApiClient.notifyTransactionError(
          txn.getSepTxId(), rpcConfig.getCustomMessages().getCustodyTransactionFailed());
    } else {
      switch (JdbcCustodyTransaction.PaymentType.from(txn.getType())) {
        case PAYMENT:
          platformApiClient.notifyOnchainFundsReceived(
              txn.getSepTxId(),
              payment.getTransactionHash(),
              payment.getAmount(),
              rpcConfig.getCustomMessages().getIncomingPaymentReceived());

          metricsService
              .counter(AnchorMetrics.PAYMENT_RECEIVED, "asset", payment.getAssetName())
              .increment(Double.parseDouble(payment.getAmount()));
          break;
        case REFUND:
          platformApiClient.notifyRefundSent(
              txn.getSepTxId(),
              payment.getTransactionHash(),
              payment.getAmount(),
              txn.getAmountFee(),
              txn.getAsset());
          break;
      }
    }
  }

  @Override
  public void onSent(JdbcCustodyTransaction txn, CustodyPayment payment)
      throws AnchorException, IOException {
    infoF(
        "Incoming outbound payment for SEP-6 transaction. Payment: id[{}], externalTxId[{}], type[{}]",
        payment.getId(),
        payment.getExternalTxId(),
        txn.getType());

    validatePayment(txn, payment);
    updateTransaction(txn, payment);

    if (CustodyTransactionStatus.FAILED == CustodyTransactionStatus.from(txn.getStatus())) {
      platformApiClient.notifyTransactionError(
          txn.getSepTxId(), rpcConfig.getCustomMessages().getCustodyTransactionFailed());
    } else {
      platformApiClient.notifyOnchainFundsSent(
          txn.getSepTxId(),
          payment.getTransactionHash(),
          rpcConfig.getCustomMessages().getOutgoingPaymentSent());

      metricsService
          .counter(AnchorMetrics.PAYMENT_SENT, "asset", payment.getAssetName())
          .increment(Double.parseDouble(payment.getAmount()));
    }
  }
}
