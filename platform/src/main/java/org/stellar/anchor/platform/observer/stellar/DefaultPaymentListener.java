package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.*;
import static org.stellar.anchor.util.AssetHelper.getSep11AssetName;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.Log.warnF;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.formatAmount;

import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPayment;
import org.stellar.anchor.ledger.PaymentTransferEvent;
import org.stellar.anchor.platform.config.RpcConfig;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.platform.service.AnchorMetrics;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.xdr.AssetType;

public class DefaultPaymentListener implements PaymentListener {
  final PaymentObservingAccountsManager paymentObservingAccountsManager;
  final JdbcSep31TransactionStore sep31TransactionStore;
  final JdbcSep24TransactionStore sep24TransactionStore;
  final JdbcSep6TransactionStore sep6TransactionStore;
  private final PlatformApiClient platformApiClient;
  private final RpcConfig rpcConfig;

  public DefaultPaymentListener(
      PaymentObservingAccountsManager paymentObservingAccountsManager,
      JdbcSep31TransactionStore sep31TransactionStore,
      JdbcSep24TransactionStore sep24TransactionStore,
      JdbcSep6TransactionStore sep6TransactionStore,
      PlatformApiClient platformApiClient,
      RpcConfig rpcConfig) {
    this.paymentObservingAccountsManager = paymentObservingAccountsManager;
    this.sep31TransactionStore = sep31TransactionStore;
    this.sep24TransactionStore = sep24TransactionStore;
    this.sep6TransactionStore = sep6TransactionStore;
    this.platformApiClient = platformApiClient;
    this.rpcConfig = rpcConfig;
  }

  @Override
  public void onReceived(PaymentTransferEvent paymentTransferEvent) {
    debugF(
        "Received payment transfer event: {}",
        GsonUtils.getInstance().toJson(paymentTransferEvent));
    LedgerTransaction ledgerTransaction = paymentTransferEvent.getLedgerTransaction();
    LedgerPayment ledgerPayment = null;
    for (LedgerTransaction.LedgerOperation operation : ledgerTransaction.getOperations()) {
      switch (operation.getType()) {
        case PAYMENT:
          if (operation
              .getPaymentOperation()
              .getId()
              .equals(String.valueOf(paymentTransferEvent.getOperationId()))) {
            ledgerPayment = operation.getPaymentOperation();
          }
          break;
        case PATH_PAYMENT_STRICT_RECEIVE, PATH_PAYMENT_STRICT_SEND:
          if (operation
              .getPathPaymentOperation()
              .getId()
              .equals(String.valueOf(paymentTransferEvent.getOperationId()))) {
            ledgerPayment = operation.getPathPaymentOperation();
          }
          break;
        case INVOKE_HOST_FUNCTION:
          if (operation
              .getInvokeHostFunctionOperation()
              .getId()
              .equals(String.valueOf(paymentTransferEvent.getOperationId()))) {
            ledgerPayment = operation.getInvokeHostFunctionOperation();
          }
          break;
        default:
          // Ignore other operation types
          break;
      }
    }
    if (ledgerPayment != null) {
      // Check if the payment is to or from an account we are observing
      if (paymentObservingAccountsManager.lookupAndUpdate(ledgerPayment.getTo())
          || paymentObservingAccountsManager.lookupAndUpdate(ledgerPayment.getFrom()))
        processAndDispatchLedgerPayment(ledgerTransaction, ledgerPayment);
    }
  }

  void processAndDispatchLedgerPayment(
      LedgerTransaction ledgerTransaction, LedgerPayment ledgerPayment) {
    if (!validate(ledgerTransaction, ledgerPayment)) {
      return;
    }

    String memo = MemoHelper.xdrMemoToString(ledgerTransaction.getMemo());

    // Find a transaction matching the memo, assumes transactions are unique to account+memo
    try {
      JdbcSep31Transaction sep31Txn =
          sep31TransactionStore.findByToAccountAndMemoAndStatus(
              ledgerPayment.getTo(), memo, SepTransactionStatus.PENDING_SENDER.toString());
      if (sep31Txn != null) {
        try {
          handleSep31Transaction(ledgerTransaction, ledgerPayment, sep31Txn);
          return;
        } catch (AnchorException aex) {
          warnF("Error handling the SEP31 transaction id={}.", sep31Txn.getId());
          errorEx(aex);
          return;
        }
      }
    } catch (Exception ex) {
      errorEx(ex);
    }

    // Find a transaction matching the memo, assumes transactions are unique to account+memo
    try {
      // TODO: replace the query with this when SAC memo is supported.
      //      JdbcSep24Transaction sep24Txn =
      //          sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
      //              ledgerPayment.getTo(),
      //              memo,
      //              SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
      JdbcSep24Transaction sep24Txn =
          sep24TransactionStore.findFirstByToAccountAndFromAccountAndStatusOrderByStartedAtDesc(
              ledgerPayment.getTo(),
              ledgerPayment.getFrom(),
              SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
      if (sep24Txn != null) {
        try {
          handleSep24Transaction(ledgerTransaction, ledgerPayment, sep24Txn);
          return;
        } catch (AnchorException aex) {
          warnF("Error handling the SEP24 transaction id={}.", sep24Txn.getId());
          errorEx(aex);
        }
      }
    } catch (Exception ex) {
      errorEx(ex);
    }

    // Find a transaction matching the memo, assumes transactions are unique to account+memo

    try {
      // TODO: replace the query with this when SAC memo is supported.
      //      JdbcSep6Transaction sep6Txn =
      //          sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
      //              ledgerPayment.getTo(),
      //              memo,
      //              SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());

      JdbcSep6Transaction sep6Txn =
          sep6TransactionStore.findOneByWithdrawAnchorAccountAndFromAccountAndStatus(
              ledgerPayment.getTo(),
              ledgerPayment.getFrom(),
              SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());

      if (sep6Txn != null) {
        try {
          handleSep6Transaction(ledgerTransaction, ledgerPayment, sep6Txn);
        } catch (AnchorException aex) {
          warnF("Error handling the SEP6 transaction id={}.", sep6Txn.getId());
          errorEx(aex);
        }
      }
    } catch (Exception ex) {
      errorEx(ex);
    }
  }

  void handleSep31Transaction(
      LedgerTransaction ledgerTransaction,
      LedgerPayment ledgerPayment,
      JdbcSepTransaction sepTransaction)
      throws AnchorException, IOException {

    checkAndWarnAssetAmountMismatch(ledgerTransaction, ledgerPayment, sepTransaction);

    platformApiClient.notifyOnchainFundsReceived(
        sepTransaction.getId(),
        ledgerTransaction.getHash(),
        AssetHelper.fromXdrAmount(ledgerPayment.getAmount()),
        rpcConfig.getCustomMessages().getIncomingPaymentReceived());

    // Update metrics
    Metrics.counter(
            AnchorMetrics.SEP31_TRANSACTION_OBSERVED.toString(),
            "status",
            SepTransactionStatus.PENDING_RECEIVER.toString())
        .increment();
    Metrics.counter(
            AnchorMetrics.PAYMENT_RECEIVED.toString(),
            "asset",
            getSep11AssetName(ledgerPayment.getAsset()))
        .increment(ledgerPayment.getAmount().doubleValue());
  }

  void handleSep24Transaction(
      LedgerTransaction ledgerTransaction,
      LedgerPayment ledgerPayment,
      JdbcSepTransaction sepTransaction)
      throws AnchorException, IOException {

    checkAndWarnAssetAmountMismatch(ledgerTransaction, ledgerPayment, sepTransaction);
    JdbcSep24Transaction sep24Txn = (JdbcSep24Transaction) sepTransaction;

    if (DEPOSIT.getKind().equals(sep24Txn.getKind())) {
      platformApiClient.notifyOnchainFundsSent(
          sepTransaction.getId(),
          ledgerTransaction.getHash(),
          rpcConfig.getCustomMessages().getOutgoingPaymentSent());
    } else if (WITHDRAWAL.getKind().equals(sep24Txn.getKind())) {
      platformApiClient.notifyOnchainFundsReceived(
          sepTransaction.getId(),
          ledgerTransaction.getHash(),
          AssetHelper.fromXdrAmount(ledgerPayment.getAmount()),
          rpcConfig.getCustomMessages().getIncomingPaymentReceived());
    } else {
      throw new IllegalStateException(
          "SEP-24 transaction kind is not supported: " + sep24Txn.getKind());
    }

    Metrics.counter(
            AnchorMetrics.SEP24_TRANSACTION_OBSERVED.toString(),
            "status",
            SepTransactionStatus.PENDING_ANCHOR.toString())
        .increment();
    Metrics.counter(
            AnchorMetrics.PAYMENT_RECEIVED.toString(),
            "asset",
            getSep11AssetName(ledgerPayment.getAsset()))
        .increment(ledgerPayment.getAmount().doubleValue());
  }

  void handleSep6Transaction(
      LedgerTransaction ledgerTransaction,
      LedgerPayment ledgerPayment,
      JdbcSepTransaction sepTransaction)
      throws AnchorException, IOException {

    checkAndWarnAssetAmountMismatch(ledgerTransaction, ledgerPayment, sepTransaction);

    JdbcSep6Transaction sep6Txn = (JdbcSep6Transaction) sepTransaction;
    if (DEPOSIT.getKind().equals(sep6Txn.getKind())
        || DEPOSIT_EXCHANGE.getKind().equals(sep6Txn.getKind())) {
      platformApiClient.notifyOnchainFundsSent(
          sepTransaction.getId(),
          ledgerTransaction.getHash(),
          rpcConfig.getCustomMessages().getOutgoingPaymentSent());
    } else if (WITHDRAWAL.getKind().equals(sep6Txn.getKind())
        || WITHDRAWAL_EXCHANGE.getKind().equals(sep6Txn.getKind())) {
      platformApiClient.notifyOnchainFundsReceived(
          sepTransaction.getId(),
          ledgerTransaction.getHash(),
          AssetHelper.fromXdrAmount(ledgerPayment.getAmount()),
          rpcConfig.getCustomMessages().getIncomingPaymentReceived());
    } else {
      throw new IllegalStateException(
          "SEP-6 transaction kind is not supported: " + sep6Txn.getKind());
    }

    Metrics.counter(
            AnchorMetrics.SEP6_TRANSACTION_OBSERVED.toString(),
            "status",
            SepTransactionStatus.PENDING_ANCHOR.toString())
        .increment();
    Metrics.counter(
            AnchorMetrics.PAYMENT_RECEIVED.toString(),
            "asset",
            getSep11AssetName(ledgerPayment.getAsset()))
        .increment(ledgerPayment.getAmount().doubleValue());
  }

  boolean validate(LedgerTransaction ledgerTransaction, LedgerPayment ledgerPayment) {
    // TODO: Enable this validation when SAC memo is supported.
    //    if (isEmpty(ledgerTransaction.getHash())
    //        || ledgerTransaction.getMemo() == null
    //        || isEmpty(MemoHelper.xdrMemoToString(ledgerTransaction.getMemo()))) {
    //      // The transaction do not have a hash or memo.
    //      // We do not process it.
    //      debugF(
    //          "Transaction {} does not have a hash or memo. This indicates a potential bug from
    // stellar network events.",
    //          ledgerTransaction.getHash());
    //      return false;
    //    }

    if (!List.of(
            AssetType.ASSET_TYPE_NATIVE,
            AssetType.ASSET_TYPE_CREDIT_ALPHANUM4,
            AssetType.ASSET_TYPE_CREDIT_ALPHANUM12)
        .contains(ledgerPayment.getAsset().getDiscriminant())) {
      // unsupported asset type
      debugF(
          "{} is not a native or an issued asset.",
          GsonUtils.getInstance().toJson(ledgerPayment.getAsset()));
      return false;
    }
    return true;
  }

  void checkAndWarnAssetAmountMismatch(
      LedgerTransaction ledgerTransaction,
      LedgerPayment ledgerPayment,
      JdbcSepTransaction sepTransaction) {
    // Compare asset code
    String paymentAssetName = "stellar:" + getSep11AssetName(ledgerPayment.getAsset());
    if (!sepTransaction.getAmountInAsset().equals(paymentAssetName)) {
      debugF(
          "Payment asset {} does not match the expected asset {}.",
          paymentAssetName,
          sepTransaction.getAmountInAsset());
    }

    // Check if the payment contains the expected amount (or greater)
    BigDecimal expectedAmount = decimal(sepTransaction.getAmountExpected());
    BigDecimal gotAmount = decimal(AssetHelper.fromXdrAmount(ledgerPayment.getAmount()));
    if (expectedAmount == null || gotAmount.compareTo(expectedAmount) >= 0) {
      debugF(
          "Incoming payment for SEP-{} transaction. sepTxn.id={}, ledgerTxn.id={}",
          sepTransaction.getProtocol(),
          sepTransaction.getId(),
          ledgerTransaction.getHash());
    } else {
      debugF(
          "The incoming payment amount was insufficient from Expected: {}, Received: {}",
          formatAmount(expectedAmount),
          formatAmount(gotAmount));
    }
  }
}
