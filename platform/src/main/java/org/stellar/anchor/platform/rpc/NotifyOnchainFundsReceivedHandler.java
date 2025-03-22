package org.stellar.anchor.platform.rpc;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL_EXCHANGE;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_ONCHAIN_FUNDS_RECEIVED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.*;
import static org.stellar.anchor.platform.utils.PaymentsUtil.addStellarTransaction;
import static org.stellar.anchor.util.Log.errorEx;

import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.Set;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.api.exception.NotFoundException;
import org.stellar.anchor.api.exception.rpc.InternalErrorException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.AmountAssetRequest;
import org.stellar.anchor.api.rpc.method.NotifyOnchainFundsReceivedRequest;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.ledger.LedgerClient;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep31Transaction;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.utils.AssetValidationUtils;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;

public class NotifyOnchainFundsReceivedHandler
    extends RpcTransactionStatusHandler<NotifyOnchainFundsReceivedRequest> {

  private final LedgerClient ledgerClient;

  public NotifyOnchainFundsReceivedHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      LedgerClient ledgerClient,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService) {
    super(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService,
        NotifyOnchainFundsReceivedRequest.class);
    this.ledgerClient = ledgerClient;
  }

  @Override
  protected void validate(JdbcSepTransaction txn, NotifyOnchainFundsReceivedRequest request)
      throws InvalidParamsException, InvalidRequestException, BadRequestException {
    super.validate(txn, request);

    if (!((request.getAmountIn() == null
            && request.getAmountOut() == null
            && request.getFeeDetails() == null)
        || (request.getAmountIn() != null
            && request.getAmountOut() != null
            && request.getFeeDetails() != null)
        || (request.getAmountIn() != null
            && request.getAmountOut() == null
            && request.getFeeDetails() == null))) {
      throw new InvalidParamsException(
          "Invalid amounts combination provided: all, none or only amount_in should be set");
    }

    if (request.getAmountIn() != null) {
      AssetValidationUtils.validateAssetAmount(
          "amount_in",
          AmountAssetRequest.builder()
              .amount(request.getAmountIn().getAmount())
              .asset(txn.getAmountInAsset())
              .build(),
          assetService);
    }
    if (request.getAmountOut() != null) {
      AssetValidationUtils.validateAssetAmount(
          "amount_out",
          AmountAssetRequest.builder()
              .amount(request.getAmountOut().getAmount())
              .asset(txn.getAmountOutAsset())
              .build(),
          assetService);
    }
    if (request.getFeeDetails() != null) {
      AssetValidationUtils.validateFeeDetails(request.getFeeDetails(), txn, assetService);
    }
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_ONCHAIN_FUNDS_RECEIVED;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyOnchainFundsReceivedRequest request)
      throws InvalidRequestException {
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
      case SEP_24:
        return PENDING_ANCHOR;
      case SEP_31:
        return PENDING_RECEIVER;
      default:
        throw new InvalidRequestException(
            String.format(
                "RPC method[%s] is not supported for protocol[%s]",
                getRpcMethod(), txn.getProtocol()));
    }
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    Set<SepTransactionStatus> supportedStatuses = new HashSet<>();
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (ImmutableSet.of(WITHDRAWAL, WITHDRAWAL_EXCHANGE).contains(Kind.from(txn6.getKind()))) {
          supportedStatuses.add(PENDING_USR_TRANSFER_START);
          supportedStatuses.add(ON_HOLD);
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (WITHDRAWAL == Kind.from(txn24.getKind())) {
          supportedStatuses.add(PENDING_USR_TRANSFER_START);
          supportedStatuses.add(ON_HOLD);
        }
        break;
      case SEP_31:
        supportedStatuses.add(PENDING_SENDER);
    }
    return supportedStatuses;
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyOnchainFundsReceivedRequest request) throws AnchorException {
    String stellarTxnId = request.getStellarTransactionId();
    try {
      LedgerTransaction ledgerTxn = ledgerClient.getTransaction(stellarTxnId);
      if (ledgerTxn == null) {
        throw new NotFoundException(String.format("Transaction (id=%s) not found"));
      }

      addStellarTransaction(txn, ledgerTxn);

      if (Sep.SEP_31.equals(Sep.from(txn.getProtocol()))) {
        JdbcSep31Transaction txn31 = (JdbcSep31Transaction) txn;
        txn31.setFromAccount(ledgerTxn.getSourceAccount());
      }
    } catch (LedgerException ex) {
      errorEx(String.format("Failed to retrieve stellar transaction by ID[%s]", stellarTxnId), ex);
      throw new InternalErrorException(
          String.format("Failed to retrieve Stellar transaction by ID[%s]", stellarTxnId), ex);
    }

    if (request.getAmountIn() != null) {
      txn.setAmountIn(request.getAmountIn().getAmount());
    }
    if (request.getAmountOut() != null) {
      txn.setAmountOut(request.getAmountOut().getAmount());
    }
    if (request.getFeeDetails() != null) {
      txn.setAmountFee(request.getFeeDetails().getTotal());
      txn.setFeeDetailsList(request.getFeeDetails().getDetails());
    }
  }
}
