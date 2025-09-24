package org.stellar.anchor.platform.rpc;

import static java.util.Collections.emptySet;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL_EXCHANGE;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_OFFCHAIN_FUNDS_AVAILABLE;
import static org.stellar.anchor.api.sep.SepTransactionStatus.*;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.NotifyOffchainFundsAvailableRequest;
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

public class NotifyOffchainFundsAvailableHandler
    extends RpcMethodHandler<NotifyOffchainFundsAvailableRequest> {

  public NotifyOffchainFundsAvailableHandler(
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
        NotifyOffchainFundsAvailableRequest.class);
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_OFFCHAIN_FUNDS_AVAILABLE;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyOffchainFundsAvailableRequest request)
      throws InvalidRequestException {
    return PENDING_USR_TRANSFER_COMPLETE;
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (ImmutableSet.of(WITHDRAWAL, WITHDRAWAL_EXCHANGE).contains(Kind.from(txn6.getKind()))
            && areFundsReceived(txn6)) {
          return Set.of(PENDING_ANCHOR, ON_HOLD);
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (WITHDRAWAL == Kind.from(txn24.getKind()) && areFundsReceived(txn24)) {
          return Set.of(PENDING_ANCHOR, ON_HOLD);
        }
        break;
      default:
        break;
    }
    return emptySet();
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyOffchainFundsAvailableRequest request) {
    if (request.getExternalTransactionId() != null) {
      txn.setExternalTransactionId(request.getExternalTransactionId());
    }
  }
}
