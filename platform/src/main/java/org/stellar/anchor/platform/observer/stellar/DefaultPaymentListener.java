package org.stellar.anchor.platform.observer.stellar;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.*;
import static org.stellar.anchor.util.AssetHelper.getSep11AssetName;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.anchor.util.Log.warnF;
import static org.stellar.anchor.util.MathHelper.decimal;
import static org.stellar.anchor.util.MathHelper.formatAmount;
import static org.stellar.anchor.util.MemoHelper.*;
import static org.stellar.anchor.util.SepHelper.AccountType.*;
import static org.stellar.anchor.util.SepHelper.accountType;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerInvokeHostFunctionOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPayment;
import org.stellar.anchor.ledger.PaymentTransferEvent;
import org.stellar.anchor.platform.config.RpcConfig;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.platform.observer.PaymentListener;
import org.stellar.anchor.platform.service.AnchorMetrics;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.sdk.Memo;
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.xdr.AssetType;
import org.stellar.sdk.xdr.MemoType;
import org.stellar.sdk.xdr.OperationType;

public class DefaultPaymentListener implements PaymentListener {
  /**
   * A fixed memo that is not expected to match any stored transaction. A lookup that fails is
   * repeated with it to tell a failing database (the probe fails too) from a failure caused by the
   * payment's own input. The probe result is discarded, so a match would be harmless.
   */
  static final String LOOKUP_PROBE_MEMO = "anchor-platform-db-probe";

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
  public void onReceived(PaymentTransferEvent paymentTransferEvent) throws IOException {
    debugF(
        "Received payment transfer event: {}",
        GsonUtils.getInstance().toJson(paymentTransferEvent));
    try {
      receive(paymentTransferEvent);
    } catch (RuntimeException rex) {
      errorF(
          "Skipping payment transfer event that cannot be processed: txHash={}, opId={}, ex={}",
          paymentTransferEvent.getTxHash(),
          paymentTransferEvent.getOperationId(),
          rex.toString());
      Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_EVENT_SKIPPED.toString()).increment();
    }
  }

  void receive(PaymentTransferEvent paymentTransferEvent) throws IOException {
    LedgerTransaction ledgerTransaction = paymentTransferEvent.getLedgerTransaction();
    if (ledgerTransaction == null || ledgerTransaction.getOperations() == null) {
      warnF(
          "Payment transfer event has no ledger transaction: txHash={}, opId={}",
          paymentTransferEvent.getTxHash(),
          paymentTransferEvent.getOperationId());
      return;
    }
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
      processAndDispatchLedgerPayment(ledgerTransaction, ledgerPayment, paymentTransferEvent);
    }
  }

  void processAndDispatchLedgerPayment(
      LedgerTransaction ledgerTransaction,
      LedgerPayment ledgerPayment,
      PaymentTransferEvent paymentTransferEvent)
      throws IOException {
    if (!validate(ledgerTransaction, ledgerPayment, paymentTransferEvent)) {
      return;
    }

    // ledgerPayment's asset/amount are now known to be one of the supported asset types
    // (validate() above rejects anything else), so it's safe to compute its sep-11 asset name for
    // this cross-check. paymentTransferEvent's amount/asset are computed independently by the
    // observer backend (e.g. Horizon's own operation indexing); if they disagree with what was
    // parsed from the ledger transaction, refuse to process rather than trust either value
    // silently.
    String eventAsset = paymentTransferEvent.getSep11Asset();
    String ledgerAsset = getSep11AssetName(ledgerPayment.getAsset());
    BigInteger eventAmount = paymentTransferEvent.getAmount();
    BigInteger ledgerAmount = ledgerPayment.getAmount();
    if (!Objects.equals(eventAsset, ledgerAsset) || !Objects.equals(eventAmount, ledgerAmount)) {
      errorF(
          "Payment observer amount/asset mismatch between independently-computed event data "
              + "and ledger-parsed data. txHash={}, opId={}, eventAsset={}, ledgerAsset={}, "
              + "eventAmount={}, ledgerAmount={}. Refusing to process.",
          ledgerTransaction.getHash(),
          paymentTransferEvent.getOperationId(),
          eventAsset,
          ledgerAsset,
          eventAmount,
          ledgerAmount);
      Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_AMOUNT_ASSET_MISMATCH.toString()).increment();
      return;
    }

    try {
      String memo = xdrMemoToString(ledgerTransaction.getMemo());
      // PostgreSQL rejects a NUL in a text parameter, so a text memo holding one can never match.
      // Only a contract-to-muxed payment gets here with such a memo (validate rejects the rest),
      // and the muxed-id lookup below still applies to it.
      List<JdbcSep31Transaction> sep31Txns =
          memo != null && memo.indexOf('\0') >= 0
              ? List.of()
              : lookup(
                  "31",
                  ledgerTransaction,
                  ledgerPayment,
                  sep31TransactionStore::findAllByToAccountAndMemoAndStatus,
                  ledgerPayment.getTo(),
                  memo,
                  SepTransactionStatus.PENDING_SENDER.toString());
      if (sep31Txns.isEmpty() && ledgerPayment.getTo().startsWith("M")) {
        MuxedAccount muxedAccount = new MuxedAccount(ledgerPayment.getTo());
        sep31Txns =
            lookup(
                "31",
                ledgerTransaction,
                ledgerPayment,
                sep31TransactionStore::findAllByToAccountAndMemoAndStatus,
                muxedAccount.getAccountId(),
                String.valueOf(muxedAccount.getMuxedId()),
                SepTransactionStatus.PENDING_SENDER.toString());
      }
      if (sep31Txns.size() > 1) {
        List<String> ids = sep31Txns.stream().map(JdbcSep31Transaction::getId).toList();
        errorF(
            "Ambiguous SEP-31 payment routing: paymentId={}, txHash={}, toAccount={}, memo={}, status={}, matchedIds={}",
            ledgerPayment.getId(),
            ledgerTransaction.getHash(),
            ledgerPayment.getTo(),
            memo,
            SepTransactionStatus.PENDING_SENDER,
            ids);
        Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_AMBIGUOUS_ROUTING.toString(), "sep", "31")
            .increment();
        return;
      }
      if (!sep31Txns.isEmpty()) {
        JdbcSep31Transaction sep31Txn = sep31Txns.get(0);
        try {
          handleSep31Transaction(ledgerTransaction, ledgerPayment, sep31Txn);
          return;
        } catch (AnchorException aex) {
          warnF("Error handling the SEP31 transaction id={}.", sep31Txn.getId());
          errorEx(aex);
          return;
        }
      }
    } catch (IOException ioex) {
      throw ioex;
    } catch (LookupSkippedException skipped) {
      // Already logged and counted by lookup(); fall through to the next protocol.
    } catch (Exception ex) {
      errorEx(ex);
      Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_EVENT_SKIPPED.toString()).increment();
    }

    // For SEP-24 and SEP-6, we need to check the memo and the destination account.
    // We need to handle the case where a C-account sends a payment to a G-account with a memo.
    // In this case, the memo is the muxed-id of the muxed account.
    Memo memo;
    String toAccount;
    if (isContractToMuxed(ledgerPayment)) {
      MuxedAccount muxedAccount = new MuxedAccount(ledgerPayment.getTo());
      toAccount = muxedAccount.getAccountId();
      memo = Memo.id(Objects.requireNonNull(muxedAccount.getMuxedId()));
    } else {
      toAccount = ledgerPayment.getTo();
      memo = Memo.fromXdr(ledgerTransaction.getMemo());
    }

    try {
      List<JdbcSep24Transaction> sep24Txns =
          lookup(
              "24",
              ledgerTransaction,
              ledgerPayment,
              sep24TransactionStore::findAllByWithdrawAnchorAccountAndMemoAndStatus,
              toAccount,
              memoAsString(memo),
              SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
      if (sep24Txns.isEmpty() && toAccount.startsWith("M")) {
        MuxedAccount muxedAccount = new MuxedAccount(toAccount);
        sep24Txns =
            lookup(
                "24",
                ledgerTransaction,
                ledgerPayment,
                sep24TransactionStore::findAllByWithdrawAnchorAccountAndMemoAndStatus,
                muxedAccount.getAccountId(),
                String.valueOf(muxedAccount.getMuxedId()),
                SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
      }
      if (sep24Txns.size() > 1) {
        List<String> ids = sep24Txns.stream().map(JdbcSep24Transaction::getId).toList();
        errorF(
            "Ambiguous SEP-24 payment routing: paymentId={}, txHash={}, toAccount={}, memo={}, status={}, matchedIds={}",
            ledgerPayment.getId(),
            ledgerTransaction.getHash(),
            toAccount,
            memoAsString(memo),
            SepTransactionStatus.PENDING_USR_TRANSFER_START,
            ids);
        Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_AMBIGUOUS_ROUTING.toString(), "sep", "24")
            .increment();
        return;
      }
      if (!sep24Txns.isEmpty()) {
        JdbcSep24Transaction sep24Txn = sep24Txns.get(0);
        try {
          handleSep24Transaction(ledgerTransaction, ledgerPayment, sep24Txn);
          return;
        } catch (AnchorException aex) {
          warnF("Error handling the SEP24 transaction id={}.", sep24Txn.getId());
          errorEx(aex);
        }
      }
    } catch (IOException ioex) {
      throw ioex;
    } catch (LookupSkippedException skipped) {
      // Already logged and counted by lookup(); fall through to the next protocol.
    } catch (Exception ex) {
      errorEx(ex);
      Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_EVENT_SKIPPED.toString()).increment();
    }

    try {
      List<JdbcSep6Transaction> sep6Txns =
          lookup(
              "6",
              ledgerTransaction,
              ledgerPayment,
              sep6TransactionStore::findAllByWithdrawAnchorAccountAndMemoAndStatus,
              toAccount,
              memoAsString(memo),
              SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
      if (sep6Txns.isEmpty() && toAccount.startsWith("M")) {
        MuxedAccount muxedAccount = new MuxedAccount(toAccount);
        sep6Txns =
            lookup(
                "6",
                ledgerTransaction,
                ledgerPayment,
                sep6TransactionStore::findAllByWithdrawAnchorAccountAndMemoAndStatus,
                muxedAccount.getAccountId(),
                String.valueOf(muxedAccount.getMuxedId()),
                SepTransactionStatus.PENDING_USR_TRANSFER_START.toString());
      }
      if (sep6Txns.size() > 1) {
        List<String> ids = sep6Txns.stream().map(JdbcSep6Transaction::getId).toList();
        errorF(
            "Ambiguous SEP-6 payment routing: paymentId={}, txHash={}, toAccount={}, memo={}, status={}, matchedIds={}",
            ledgerPayment.getId(),
            ledgerTransaction.getHash(),
            toAccount,
            memoAsString(memo),
            SepTransactionStatus.PENDING_USR_TRANSFER_START,
            ids);
        Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_AMBIGUOUS_ROUTING.toString(), "sep", "6")
            .increment();
        return;
      }
      if (!sep6Txns.isEmpty()) {
        JdbcSep6Transaction sep6Txn = sep6Txns.get(0);
        try {
          handleSep6Transaction(ledgerTransaction, ledgerPayment, sep6Txn);
        } catch (AnchorException aex) {
          warnF("Error handling the SEP6 transaction id={}.", sep6Txn.getId());
          errorEx(aex);
        }
      }
    } catch (IOException ioex) {
      throw ioex;
    } catch (LookupSkippedException skipped) {
      // Already logged and counted by lookup(); fall through to the next protocol.
    } catch (Exception ex) {
      errorEx(ex);
      Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_EVENT_SKIPPED.toString()).increment();
    }
  }

  @FunctionalInterface
  private interface TransactionLookup<T> {
    List<T> find(String account, String memo, String status);
  }

  /** Signals that a lookup was skipped because of the payment's own input; already logged. */
  private static class LookupSkippedException extends RuntimeException {
    LookupSkippedException(Throwable cause) {
      super(cause);
    }
  }

  /**
   * Runs a transaction-store lookup. A database failure must not advance the observer cursor, or
   * the payment is never processed again, so it is rethrown as an IOException, which reuses the
   * retry path of a failed Platform API notification. A failure caused by this payment's own input
   * would stall the observer forever, so the lookup is repeated with a fixed memo to tell the two
   * apart: if that probe fails, the database is failing and the payment is held; if it succeeds and
   * the original lookup fails again, the probe runs once more, and only when that also succeeds is
   * the payment skipped. A failure to get a connection cannot come from the payment's input, so it
   * holds without a probe.
   */
  private <T> List<T> lookup(
      String sep,
      LedgerTransaction ledgerTransaction,
      LedgerPayment ledgerPayment,
      TransactionLookup<T> lookup,
      String account,
      String memo,
      String status)
      throws IOException {
    try {
      return lookup.find(account, memo, status);
    } catch (DataAccessException | TransactionException firstFailure) {
      if (isConnectionFailure(firstFailure) || databaseIsFailing(lookup, account, status)) {
        throw holdPayment(sep, ledgerTransaction, firstFailure);
      }
      try {
        return lookup.find(account, memo, status);
      } catch (DataAccessException | TransactionException secondFailure) {
        if (isConnectionFailure(secondFailure) || databaseIsFailing(lookup, account, status)) {
          throw holdPayment(sep, ledgerTransaction, secondFailure);
        }
        errorF(
            "SEP-{} transaction lookup failed for this payment only; skipping it. txHash={}, opId={}, toAccount={}",
            sep,
            ledgerTransaction.getHash(),
            ledgerPayment.getId(),
            account);
        errorEx(secondFailure);
        Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_EVENT_SKIPPED.toString()).increment();
        throw new LookupSkippedException(secondFailure);
      }
    }
  }

  private static boolean isConnectionFailure(RuntimeException ex) {
    return ex instanceof CannotGetJdbcConnectionException
        || ex instanceof CannotCreateTransactionException;
  }

  /** Repeats the lookup with the probe memo; true when that fails too. */
  private <T> boolean databaseIsFailing(
      TransactionLookup<T> lookup, String account, String status) {
    try {
      lookup.find(account, LOOKUP_PROBE_MEMO, status);
      return false;
    } catch (Exception probeFailure) {
      errorEx("The probe lookup failed too.", probeFailure);
      return true;
    }
  }

  private IOException holdPayment(
      String sep, LedgerTransaction ledgerTransaction, RuntimeException cause) {
    errorF(
        "SEP-{} transaction lookup failed on the database. The payment will be retried. txHash={}",
        sep,
        ledgerTransaction.getHash());
    errorEx(cause);
    Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_LOOKUP_FAILED.toString(), "sep", sep)
        .increment();
    return new IOException("SEP-" + sep + " transaction lookup failed on the database", cause);
  }

  void handleSep31Transaction(
      LedgerTransaction ledgerTransaction,
      LedgerPayment ledgerPayment,
      JdbcSepTransaction sepTransaction)
      throws AnchorException, IOException {

    if (!checkAssetAmountSufficient(ledgerTransaction, ledgerPayment, sepTransaction, true)) {
      return;
    }

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

    JdbcSep24Transaction sep24Txn = (JdbcSep24Transaction) sepTransaction;
    boolean isWithdrawal = WITHDRAWAL.getKind().equals(sep24Txn.getKind());
    if (!checkAssetAmountSufficient(
        ledgerTransaction, ledgerPayment, sepTransaction, isWithdrawal)) {
      return;
    }

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

    JdbcSep6Transaction sep6Txn = (JdbcSep6Transaction) sepTransaction;
    boolean isWithdrawal =
        WITHDRAWAL.getKind().equals(sep6Txn.getKind())
            || WITHDRAWAL_EXCHANGE.getKind().equals(sep6Txn.getKind());
    if (!checkAssetAmountSufficient(
        ledgerTransaction, ledgerPayment, sepTransaction, isWithdrawal)) {
      return;
    }

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

  boolean validate(
      LedgerTransaction ledgerTransaction,
      LedgerPayment ledgerPayment,
      PaymentTransferEvent paymentTransferEvent) {
    if (isEmpty(ledgerTransaction.getHash())) {
      debugF(
          "Transaction {} does not have a hash. This indicates a potential bug from stellar network events.",
          ledgerTransaction.getHash());
      return false;
    }

    if (ledgerTransaction.getMemo() == null) {
      debugF(
          "Transaction {} with a null memo. This indicates a potential bug from stellar network events.",
          ledgerTransaction.getHash());
      return false;
    }

    byte[] textMemoBytes = null;
    if (ledgerTransaction.getMemo().getDiscriminant() == MemoType.MEMO_TEXT) {
      byte[] memoBytes = ledgerTransaction.getMemo().getText().getBytes();
      textMemoBytes = memoBytes;
      if (memoBytes.length == 0) {
        debugF(
            "Transaction {} with an empty text memo. This indicates a potential bug from stellar network events.",
            ledgerTransaction.getHash());
        return false;
      }
    }

    if (ledgerPayment.getType() == OperationType.INVOKE_HOST_FUNCTION) {
      LedgerInvokeHostFunctionOperation invokeOp =
          (LedgerInvokeHostFunctionOperation) ledgerPayment;
      String eventAsset = paymentTransferEvent.getSep11Asset();
      if (isEmpty(eventAsset)
          || paymentTransferEvent.getAmount() == null
          || isEmpty(paymentTransferEvent.getFrom())
          || isEmpty(paymentTransferEvent.getTo())) {
        debugF(
            "Operation {} moved no SAC balance to/from an observed account.",
            ledgerPayment.getId());
        return false;
      }
      invokeOp.setAsset(org.stellar.sdk.Asset.create(eventAsset).toXdr());
      invokeOp.setAmount(paymentTransferEvent.getAmount());
      invokeOp.setFrom(paymentTransferEvent.getFrom());
      invokeOp.setTo(paymentTransferEvent.getTo());
    }

    if (isEmpty(ledgerPayment.getFrom()) || isEmpty(ledgerPayment.getTo())) {
      debugF("Operation {} has no source or destination account.", ledgerPayment.getId());
      return false;
    }

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

    // PostgreSQL rejects a NUL in a text parameter, and no stored memo can contain one, so a
    // payment whose routing uses the text memo can never match a transaction. Skip it before it
    // reaches a transaction store query. A contract-to-muxed payment is routed by the muxed id and
    // never by the text memo, so it is not skipped.
    if (textMemoBytes != null && !isContractToMuxed(ledgerPayment)) {
      for (byte b : textMemoBytes) {
        if (b == 0) {
          warnF(
              "Skipping payment: the text memo contains a NUL byte. txHash={}, opId={}",
              ledgerTransaction.getHash(),
              ledgerPayment.getId());
          Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_EVENT_SKIPPED.toString()).increment();
          return false;
        }
      }
    }
    return true;
  }

  private static boolean isContractToMuxed(LedgerPayment ledgerPayment) {
    return accountType(ledgerPayment.getFrom()) == Contract
        && accountType(ledgerPayment.getTo()) == Muxed;
  }

  boolean checkAssetAmountSufficient(
      LedgerTransaction ledgerTransaction,
      LedgerPayment ledgerPayment,
      JdbcSepTransaction sepTransaction,
      boolean enforceAssetMatch) {
    String paymentAssetName = "stellar:" + getSep11AssetName(ledgerPayment.getAsset());
    if (!sepTransaction.getAmountInAsset().equals(paymentAssetName)) {
      if (enforceAssetMatch) {
        errorF(
            "Rejecting incoming payment for SEP-{} transaction: asset did not match. "
                + "sepTxn.id={}, ledgerTxn.id={}, expected={}, received={}",
            sepTransaction.getProtocol(),
            sepTransaction.getId(),
            ledgerTransaction.getHash(),
            sepTransaction.getAmountInAsset(),
            paymentAssetName);
        Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_AMOUNT_ASSET_MISMATCH.toString())
            .increment();
        return false;
      }
      debugF(
          "Payment asset {} does not match the expected asset {}.",
          paymentAssetName,
          sepTransaction.getAmountInAsset());
    }

    BigDecimal expectedAmount = decimal(sepTransaction.getAmountExpected());
    BigDecimal gotAmount = decimal(AssetHelper.fromXdrAmount(ledgerPayment.getAmount()));
    if (expectedAmount != null && gotAmount.compareTo(expectedAmount) < 0) {
      errorF(
          "Rejecting incoming payment for SEP-{} transaction: amount was insufficient. "
              + "sepTxn.id={}, ledgerTxn.id={}, expected={}, received={}",
          sepTransaction.getProtocol(),
          sepTransaction.getId(),
          ledgerTransaction.getHash(),
          formatAmount(expectedAmount),
          formatAmount(gotAmount));
      Metrics.counter(AnchorMetrics.PAYMENT_OBSERVER_AMOUNT_INSUFFICIENT.toString()).increment();
      return false;
    }

    debugF(
        "Incoming payment for SEP-{} transaction. sepTxn.id={}, ledgerTxn.id={}",
        sepTransaction.getProtocol(),
        sepTransaction.getId(),
        ledgerTransaction.getHash());
    return true;
  }
}
