package org.stellar.anchor.platform.service;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.formatAmount;

import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.apache.commons.codec.DecoderException;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.platform.config.RpcConfig;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.platform.observer.ObservedPayment;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.Log;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.xdr.MemoType;

public class PaymentOperationToEventListener implements PaymentListener {
  final JdbcSep31TransactionStore sep31TransactionStore;
  final JdbcSep24TransactionStore sep24TransactionStore;
  final JdbcSep6TransactionStore sep6TransactionStore;
  private final PlatformApiClient platformApiClient;
  private final RpcConfig rpcConfig;

  public PaymentOperationToEventListener(
      JdbcSep31TransactionStore sep31TransactionStore,
      JdbcSep24TransactionStore sep24TransactionStore,
      JdbcSep6TransactionStore sep6TransactionStore,
      PlatformApiClient platformApiClient,
      RpcConfig rpcConfig) {
    this.sep31TransactionStore = sep31TransactionStore;
    this.sep24TransactionStore = sep24TransactionStore;
    this.sep6TransactionStore = sep6TransactionStore;
    this.platformApiClient = platformApiClient;
    this.rpcConfig = rpcConfig;
  }

  @Override
  public void onReceived(ObservedPayment payment) throws IOException {
    String memo = null;
    String memoType;
    if (!payment.getType().equals(ObservedPayment.Type.SAC_TRANSFER)) {
      if (Objects.toString(payment.getTransactionHash(), "").isEmpty()
          || Objects.toString(payment.getTransactionMemo(), "").isEmpty()) {
        traceF("Ignore the payment {} is not connected to a transaction.", payment.getId());
        return;
      }

      // Parse memo
      memo = payment.getTransactionMemo();
      memoType = payment.getTransactionMemoType();
      if (memoType.equals(MemoHelper.memoTypeAsString(MemoType.MEMO_HASH))) {
        try {
          memo = MemoHelper.convertHexToBase64(payment.getTransactionMemo());
        } catch (DecoderException ex) {
          infoF(
              "The memo type is \"hash\" but the memo string {} could not be parsed as such.",
              memo);
        }
      }
    }

    // Check if the payment contains the expected asset type
    if (!List.of("credit_alphanum4", "credit_alphanum12", "native")
        .contains(payment.getAssetType())) {
      // Asset type does not match
      debugF("{} is not an issued asset.", payment.getAssetType());
      return;
    }

    // Find a transaction matching the memo, assumes transactions are unique to account+memo
    JdbcSep31Transaction sep31Txn = null;
    try {
      sep31Txn =
          sep31TransactionStore.findByToAccountAndMemoAndStatus(
              payment.getTo(), memo, SepTransactionStatus.PENDING_SENDER.toString());
    } catch (Exception ex) {
      errorEx(ex);
    }
    if (sep31Txn != null) {
      try {
        handleSep31Transaction(payment, sep31Txn);
        return;
      } catch (AnchorException aex) {
        warnF("Error handling the SEP31 transaction id={}.", sep31Txn.getId());
        errorEx(aex);
        return;
      }
    }

    // Find a transaction matching the memo, assumes transactions are unique to account+memo
    JdbcSep24Transaction sep24Txn = null;
    try {
      sep24Txn =
          sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
              payment.getTo(), memo, SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
    } catch (Exception ex) {
      errorEx(ex);
    }
    if (sep24Txn != null) {
      try {
        handleSep24Transaction(payment, sep24Txn);
        return;
      } catch (AnchorException aex) {
        warnF("Error handling the SEP24 transaction id={}.", sep24Txn.getId());
        errorEx(aex);
      }
    }

    // Find a transaction matching the memo, assumes transactions are unique to account+memo
    JdbcSep6Transaction sep6Txn = null;
    try {
      if (memo != null) {
        sep6Txn =
            sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
                payment.getTo(), memo, SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
      } else {
        // SAC transfers do not include a memo so we need to find the transaction by the
        // from_account
        sep6Txn =
            sep6TransactionStore.findOneByWithdrawAnchorAccountAndFromAccountAndStatus(
                payment.getTo(),
                payment.getFrom(),
                SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
      }
    } catch (Exception ex) {
      errorEx(ex);
    }
    if (sep6Txn != null) {
      try {
        handleSep6Transaction(payment, sep6Txn);
      } catch (AnchorException aex) {
        warnF("Error handling the SEP6 transaction id={}.", sep6Txn.getId());
        errorEx(aex);
      }
    }
  }

  @Override
  public void onSent(ObservedPayment payment) {
    // not implemented. NOOP.
  }

  void handleSep31Transaction(ObservedPayment payment, JdbcSep31Transaction txn)
      throws AnchorException, IOException {
    // Compare asset code
    String paymentAssetName = "stellar:" + payment.getAssetName();
    if (!txn.getAmountInAsset().equals(paymentAssetName)) {
      warnF(
          "Payment asset {} does not match the expected asset {}.",
          payment.getAssetCode(),
          txn.getAmountInAsset());
    }

    // Check if the payment contains the expected amount (or greater)
    BigDecimal expectedAmount = decimal(txn.getAmountIn());
    BigDecimal gotAmount = decimal(payment.getAmount());
    String message = "Incoming payment for SEP-31 transaction";
    if (gotAmount.compareTo(expectedAmount) >= 0) {
      Log.info(message);
    } else {
      message =
          String.format(
              "The incoming payment amount was insufficient! Expected: \"%s\", Received: \"%s\"",
              formatAmount(expectedAmount), formatAmount(gotAmount));
      Log.warn(message);
    }

    platformApiClient.notifyOnchainFundsReceived(
        txn.getId(),
        payment.getTransactionHash(),
        payment.getAmount(),
        rpcConfig.getCustomMessages().getIncomingPaymentReceived());

    // Update metrics
    Metrics.counter(
            AnchorMetrics.SEP31_TRANSACTION_OBSERVED.toString(),
            "status",
            SepTransactionStatus.PENDING_RECEIVER.toString())
        .increment();
    Metrics.counter(AnchorMetrics.PAYMENT_RECEIVED.toString(), "asset", payment.getAssetName())
        .increment(Double.parseDouble(payment.getAmount()));
  }

  void handleSep24Transaction(ObservedPayment payment, JdbcSep24Transaction txn)
      throws AnchorException, IOException {
    // Compare asset code
    String assetName =
        AssetHelper.getSep11AssetName(payment.getAssetCode(), payment.getAssetIssuer());
    if (!payment.getAssetName().equals(assetName)) {
      warnF(
          "Payment asset {} does not match the expected asset {}.",
          payment.getAssetCode(),
          assetName);
      return;
    }

    // Check if the payment contains the expected amount (or greater)
    BigDecimal amountIn = decimal(txn.getAmountIn());
    BigDecimal gotAmount = decimal(payment.getAmount());
    String message = "Incoming payment for SEP-24 transaction";
    if (gotAmount.compareTo(amountIn) == 0) {
      Log.info(message);
    } else {
      message =
          String.format(
              "The incoming payment amount was insufficient! Expected: \"%s\", Received: \"%s\"",
              formatAmount(amountIn), formatAmount(gotAmount));
      Log.warn(message);
    }

    if (DEPOSIT.getKind().equals(txn.getKind())) {
      platformApiClient.notifyOnchainFundsSent(
          txn.getId(),
          payment.getTransactionHash(),
          rpcConfig.getCustomMessages().getOutgoingPaymentSent());
    } else if (WITHDRAWAL.getKind().equals(txn.getKind())) {
      platformApiClient.notifyOnchainFundsReceived(
          txn.getId(),
          payment.getTransactionHash(),
          payment.getAmount(),
          rpcConfig.getCustomMessages().getIncomingPaymentReceived());
    }

    Metrics.counter(
            AnchorMetrics.SEP24_TRANSACTION_OBSERVED.toString(),
            "status",
            SepTransactionStatus.PENDING_ANCHOR.toString())
        .increment();
    Metrics.counter(AnchorMetrics.PAYMENT_RECEIVED.toString(), "asset", payment.getAssetName())
        .increment(Double.parseDouble(payment.getAmount()));
  }

  void handleSep6Transaction(ObservedPayment payment, JdbcSep6Transaction txn)
      throws AnchorException, IOException {
    String assetName =
        AssetHelper.getSep11AssetName(payment.getAssetCode(), payment.getAssetIssuer());
    if (!payment.getAssetName().equals(assetName)) {
      warnF(
          "Payment asset {} does not match the expected asset {}.",
          payment.getAssetCode(),
          assetName);
      return;
    }

    BigDecimal amountExpected = decimal(txn.getAmountExpected());
    BigDecimal gotAmount = decimal(payment.getAmount());
    if (amountExpected != null && gotAmount.compareTo(amountExpected) >= 0) {
      Log.infoF("Incoming payment for SEP-6 transaction {}.", txn.getId());
    } else if (amountExpected != null) {
      Log.warnF(
          "The incoming payment amount for SEP-6 transaction {} was insufficient! Expected: \"{}\", Received: \"{}\"",
          txn.getId(),
          formatAmount(amountExpected),
          formatAmount(gotAmount));
    }

    platformApiClient.notifyOnchainFundsReceived(
        txn.getId(),
        payment.getTransactionHash(),
        payment.getAmount(),
        rpcConfig.getCustomMessages().getIncomingPaymentReceived());

    Metrics.counter(
            AnchorMetrics.SEP6_TRANSACTION_OBSERVED.toString(),
            "status",
            SepTransactionStatus.PENDING_ANCHOR.toString())
        .increment();
    Metrics.counter(AnchorMetrics.PAYMENT_RECEIVED.toString(), "asset", payment.getAssetName())
        .increment(Double.parseDouble(payment.getAmount()));
  }
}
