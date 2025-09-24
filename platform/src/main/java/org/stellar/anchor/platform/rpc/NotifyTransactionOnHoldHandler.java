package org.stellar.anchor.platform.rpc;

import static java.util.Collections.emptySet;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.*;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_6;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_TRANSACTION_ON_HOLD;
import static org.stellar.anchor.api.sep.SepTransactionStatus.*;

import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.stellar.anchor.api.platform.PlatformTransactionData;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.NotifyTransactionOnHoldRequest;
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

public class NotifyTransactionOnHoldHandler
    extends RpcMethodHandler<NotifyTransactionOnHoldRequest> {

  public NotifyTransactionOnHoldHandler(
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
        NotifyTransactionOnHoldRequest.class);
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_TRANSACTION_ON_HOLD;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyTransactionOnHoldRequest request) {
    return ON_HOLD;
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    if (Set.of(SEP_6, SEP_24).contains(Sep.from(txn.getProtocol()))) {
      Set<SepTransactionStatus> supportedStatuses = new HashSet<>();
      supportedStatuses.add(PENDING_USR_TRANSFER_START);
      Sep from = Sep.from(txn.getProtocol());
      if (from == SEP_6) {
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (ImmutableSet.of(WITHDRAWAL, WITHDRAWAL_EXCHANGE)
            .contains(PlatformTransactionData.Kind.from((txn6).getKind()))) {
          supportedStatuses.add(PENDING_ANCHOR);
        }
      } else if (from == SEP_24) {
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (WITHDRAWAL == PlatformTransactionData.Kind.from(txn24.getKind())) {
          supportedStatuses.add(PENDING_ANCHOR);
        }
      }
      return supportedStatuses;
    }
    return emptySet();
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyTransactionOnHoldRequest request) {
    if (txn.getTransferReceivedAt() == null) {
      txn.setTransferReceivedAt(Instant.now());
    }
  }
}
