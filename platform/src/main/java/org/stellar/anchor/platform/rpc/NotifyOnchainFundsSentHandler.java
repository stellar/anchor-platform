package org.stellar.anchor.platform.rpc;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT_EXCHANGE;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_ONCHAIN_FUNDS_SENT;
import static org.stellar.anchor.api.sep.SepTransactionStatus.COMPLETED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_STELLAR;
import static org.stellar.anchor.platform.utils.PaymentsUtil.addStellarTransaction;
import static org.stellar.anchor.util.Log.errorEx;

import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.stellar.anchor.api.exception.rpc.InternalErrorException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.NotifyOnchainFundsSentRequest;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.horizon.Horizon;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;
import org.stellar.sdk.exception.NetworkException;
import org.stellar.sdk.responses.operations.OperationResponse;

public class NotifyOnchainFundsSentHandler
    extends RpcTransactionStatusHandler<NotifyOnchainFundsSentRequest> {

  private final Horizon horizon;

  public NotifyOnchainFundsSentHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      Horizon horizon,
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
        NotifyOnchainFundsSentRequest.class);
    this.horizon = horizon;
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_ONCHAIN_FUNDS_SENT;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyOnchainFundsSentRequest request)
      throws InvalidRequestException {
    return COMPLETED;
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    Set<SepTransactionStatus> supportedStatuses = new HashSet<>();
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(Kind.from(txn6.getKind()))) {
          supportedStatuses.add(PENDING_STELLAR);
          if (areFundsReceived(txn6)) {
            supportedStatuses.add(PENDING_ANCHOR);
          }
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (DEPOSIT == Kind.from(txn24.getKind())) {
          supportedStatuses.add(PENDING_STELLAR);
          if (areFundsReceived(txn24)) {
            supportedStatuses.add(PENDING_ANCHOR);
          }
        }
        break;
      default:
        break;
    }
    return supportedStatuses;
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyOnchainFundsSentRequest request) throws InternalErrorException {
    Instant transferReceivedAt = txn.getTransferReceivedAt();

    String stellarTxnId = request.getStellarTransactionId();
    try {
      List<OperationResponse> txnOperations = horizon.getStellarTxnOperations(stellarTxnId);
      addStellarTransaction(txn, stellarTxnId, txnOperations);
    } catch (NetworkException ex) {
      errorEx(String.format("Failed to retrieve stellar transaction by ID[%s]", stellarTxnId), ex);
      throw new InternalErrorException(
          String.format("Failed to retrieve Stellar transaction by ID[%s]", stellarTxnId), ex);
    }

    txn.setTransferReceivedAt(transferReceivedAt);
  }
}
