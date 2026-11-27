package org.stellar.anchor.platform.rpc;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT_EXCHANGE;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_OFFCHAIN_FUNDS_SENT;
import static org.stellar.anchor.api.sep.SepTransactionStatus.COMPLETED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_EXTERNAL;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_RECEIVER;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_USR_TRANSFER_COMPLETE;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_USR_TRANSFER_START;

import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.NotifyOffchainFundsSentRequest;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;

public class NotifyOffchainFundsSentHandler
    extends RpcTransactionStatusHandler<NotifyOffchainFundsSentRequest> {

  public NotifyOffchainFundsSentHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
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
        NotifyOffchainFundsSentRequest.class);
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_OFFCHAIN_FUNDS_SENT;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyOffchainFundsSentRequest request)
      throws InvalidRequestException {
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        switch (Kind.from(txn6.getKind())) {
          case DEPOSIT:
          case DEPOSIT_EXCHANGE:
            return PENDING_EXTERNAL;
          case WITHDRAWAL:
          case WITHDRAWAL_EXCHANGE:
            return COMPLETED;
          default:
            throw new InvalidRequestException(
                String.format(
                    "Kind[%s] is not supported for protocol[%s] and action[%s]",
                    txn6.getKind(), txn6.getProtocol(), getRpcMethod()));
        }
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        switch (Kind.from(txn24.getKind())) {
          case DEPOSIT:
            return PENDING_EXTERNAL;
          case WITHDRAWAL:
            return COMPLETED;
          default:
            throw new InvalidRequestException(
                String.format(
                    "Kind[%s] is not supported for protocol[%s] and action[%s]",
                    txn24.getKind(), txn24.getProtocol(), getRpcMethod()));
        }
      case SEP_31:
        return COMPLETED;
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
        switch (Kind.from(txn6.getKind())) {
          case DEPOSIT:
          case DEPOSIT_EXCHANGE:
            supportedStatuses.add(PENDING_USR_TRANSFER_START);
            break;
          case WITHDRAWAL:
          case WITHDRAWAL_EXCHANGE:
            if (areFundsReceived(txn6)) {
              supportedStatuses.add(PENDING_ANCHOR);
            }
            supportedStatuses.add(PENDING_USR_TRANSFER_COMPLETE);
            supportedStatuses.add(PENDING_EXTERNAL);
            break;
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        switch (Kind.from(txn24.getKind())) {
          case DEPOSIT:
            supportedStatuses.add(PENDING_USR_TRANSFER_START);
            break;
          case WITHDRAWAL:
            if (areFundsReceived(txn24)) {
              supportedStatuses.add(PENDING_ANCHOR);
            }
            supportedStatuses.add(PENDING_USR_TRANSFER_COMPLETE);
            supportedStatuses.add(PENDING_EXTERNAL);
            break;
        }
        break;
      case SEP_31:
        if (areFundsReceived(txn)) {
          supportedStatuses.add(PENDING_RECEIVER);
          supportedStatuses.add(PENDING_EXTERNAL);
        }
        break;
    }
    return supportedStatuses;
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyOffchainFundsSentRequest request) {
    if (request.getExternalTransactionId() != null) {
      txn.setExternalTransactionId(request.getExternalTransactionId());
      switch (Sep.from(txn.getProtocol())) {
        case SEP_6:
          JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
          if (ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(Kind.from(txn6.getKind()))) {
            if (request.getFundsSentAt() != null) {
              txn6.setTransferReceivedAt(request.getFundsSentAt());
            } else {
              txn6.setTransferReceivedAt(Instant.now());
            }
          }
          break;
        case SEP_24:
          JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
          if (DEPOSIT == Kind.from(txn24.getKind())) {
            if (request.getFundsSentAt() != null) {
              txn24.setTransferReceivedAt(request.getFundsSentAt());
            } else {
              txn24.setTransferReceivedAt(Instant.now());
            }
          }
          break;
      }
    }
  }
}
