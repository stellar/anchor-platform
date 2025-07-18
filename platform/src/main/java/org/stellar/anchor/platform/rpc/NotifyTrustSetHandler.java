package org.stellar.anchor.platform.rpc;

import static java.util.Collections.emptySet;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT_EXCHANGE;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_TRUST_SET;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_STELLAR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_TRUST;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.NotifyTrustSetRequest;
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

public class NotifyTrustSetHandler extends RpcTransactionStatusHandler<NotifyTrustSetRequest> {
  public NotifyTrustSetHandler(
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
        NotifyTrustSetRequest.class);
  }

  @Override
  protected void validate(JdbcSepTransaction txn, NotifyTrustSetRequest request)
      throws InvalidRequestException, InvalidParamsException, BadRequestException {
    super.validate(txn, request);
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_TRUST_SET;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyTrustSetRequest request) {
    if (request.isSuccess()) {
      return PENDING_ANCHOR;
    } else {
      return PENDING_STELLAR;
    }
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(Kind.from(txn6.getKind()))) {
          return Set.of(PENDING_TRUST);
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (DEPOSIT == Kind.from(txn24.getKind())) {
          return Set.of(PENDING_TRUST);
        }
        break;
      default:
        break;
    }
    return emptySet();
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyTrustSetRequest request) throws AnchorException {}
}
