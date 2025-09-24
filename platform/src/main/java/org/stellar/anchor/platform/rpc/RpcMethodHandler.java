package org.stellar.anchor.platform.rpc;

import static org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_STATUS_CHANGED;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.RECEIVE;
import static org.stellar.anchor.api.sep.SepTransactionStatus.COMPLETED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.ERROR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.EXPIRED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.REFUNDED;
import static org.stellar.anchor.event.EventService.EventQueue.TRANSACTION;
import static org.stellar.anchor.platform.service.AnchorMetrics.PLATFORM_RPC_TRANSACTION;
import static org.stellar.anchor.platform.utils.PlatformTransactionHelper.toGetTransactionResponse;
import static org.stellar.anchor.util.MetricConstants.*;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.rpc.method.RpcMethodParamsRequest;
import org.stellar.anchor.api.rpc.method.features.SupportsUserActionRequiredBy;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.event.EventService.Session;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep31Transaction;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.Log;

public abstract class RpcMethodHandler<T extends RpcMethodParamsRequest> {

  private static final Gson gson = GsonUtils.getInstance();

  protected final Sep6TransactionStore txn6Store;
  protected final Sep24TransactionStore txn24Store;
  protected final Sep31TransactionStore txn31Store;
  protected final AssetService assetService;
  private final RequestValidator requestValidator;
  private final MetricsService metricsService;
  private final Class<T> requestType;
  private final Session eventSession;

  public RpcMethodHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      EventService eventService,
      MetricsService metricsService,
      Class<T> requestType) {
    this.txn6Store = txn6Store;
    this.txn24Store = txn24Store;
    this.txn31Store = txn31Store;
    this.requestValidator = requestValidator;
    this.assetService = assetService;
    this.metricsService = metricsService;
    this.requestType = requestType;
    this.eventSession = eventService.createSession(this.getClass().getName(), TRANSACTION);
  }

  public GetTransactionResponse handle(Object requestParams) throws AnchorException {
    T request = gson.fromJson(gson.toJson(requestParams), requestType);
    Log.infoF("Processing RPC request {}", request);
    JdbcSepTransaction txn = getTransaction(request.getTransactionId());
    Log.debugF("SEP transaction before request is executed {}", txn);

    if (txn == null) {
      throw new InvalidRequestException(
          String.format("Transaction with id[%s] is not found", request.getTransactionId()));
    }

    if (!getSupportedStatuses(txn).contains(SepTransactionStatus.from(txn.getStatus()))) {
      String kind;
      switch (Sep.from(txn.getProtocol())) {
        case SEP_6:
          kind = ((JdbcSep6Transaction) txn).getKind();
          break;
        case SEP_24:
          JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
          kind = txn24.getKind();
          break;
        case SEP_31:
          kind = RECEIVE.getKind();
          break;
        default:
          kind = null;
      }
      throw new InvalidRequestException(
          String.format(
              "RPC method[%s] is not supported. Status[%s], kind[%s], protocol[%s], funds received[%b]",
              getRpcMethod(), txn.getStatus(), kind, txn.getProtocol(), areFundsReceived(txn)));
    }

    updateTransaction(txn, request);

    Log.debugF("Transaction after update is executed {}", txn);

    GetTransactionResponse txResponse = toGetTransactionResponse(txn, assetService);

    eventSession.publish(
        AnchorEvent.builder()
            .id(UUID.randomUUID().toString())
            .sep(txn.getProtocol())
            .type(TRANSACTION_STATUS_CHANGED)
            .transaction(txResponse)
            .build());

    return toGetTransactionResponse(txn, assetService);
  }

  public abstract RpcMethod getRpcMethod();

  protected abstract SepTransactionStatus getNextStatus(JdbcSepTransaction txn, T request)
      throws AnchorException;

  protected abstract Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn);

  protected abstract void updateTransactionWithRpcRequest(JdbcSepTransaction txn, T request)
      throws AnchorException;

  protected JdbcSepTransaction getTransaction(String transactionId) throws AnchorException {
    Sep31Transaction txn31 = txn31Store.findByTransactionId(transactionId);
    if (txn31 != null) {
      return (JdbcSep31Transaction) txn31;
    }
    Sep24Transaction txn24 = txn24Store.findByTransactionId(transactionId);
    if (txn24 != null) {
      return (JdbcSep24Transaction) txn24;
    }
    return (JdbcSep6Transaction) txn6Store.findByTransactionId(transactionId);
  }

  protected void validate(JdbcSepTransaction txn, T request)
      throws InvalidParamsException, InvalidRequestException, BadRequestException {
    if (request instanceof SupportsUserActionRequiredBy
        && ((SupportsUserActionRequiredBy) request).getUserActionRequiredBy() != null) {
      if (((SupportsUserActionRequiredBy) request)
          .getUserActionRequiredBy()
          .isBefore(Instant.now())) {
        throw new InvalidParamsException("user_action_required_by can not be in the past");
      }
    }

    requestValidator.validate(request);
  }

  protected void updateTransaction(JdbcSepTransaction txn, T request) throws AnchorException {
    validate(txn, request);

    SepTransactionStatus nextStatus = getNextStatus(txn, request);

    if (isErrorStatus(nextStatus) && request.getMessage() == null) {
      throw new InvalidParamsException("message is required");
    }

    boolean shouldClearMessageStatus =
        !isErrorStatus(nextStatus) && isErrorStatus(SepTransactionStatus.from(txn.getStatus()));

    txn.setUserActionRequiredBy(null);
    if (request instanceof SupportsUserActionRequiredBy
        && ((SupportsUserActionRequiredBy) request).getUserActionRequiredBy() != null) {
      txn.setUserActionRequiredBy(
          ((SupportsUserActionRequiredBy) request).getUserActionRequiredBy());
    }

    updateTransactionWithRpcRequest(txn, request);

    txn.setUpdatedAt(Instant.now());
    txn.setStatus(nextStatus.toString());

    if (isFinalStatus(nextStatus)) {
      txn.setCompletedAt(Instant.now());
    }

    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (request.getMessage() != null) {
          txn6.setMessage(request.getMessage());
        } else if (shouldClearMessageStatus) {
          txn6.setMessage(null);
        }
        txn6Store.save(txn6);
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (request.getMessage() != null) {
          txn24.setMessage(request.getMessage());
        } else if (shouldClearMessageStatus) {
          txn24.setMessage(null);
        }
        txn24Store.save(txn24);
        break;
      case SEP_31:
        JdbcSep31Transaction txn31 = (JdbcSep31Transaction) txn;
        if (request.getMessage() != null) {
          txn31.setRequiredInfoMessage(request.getMessage());
        } else if (shouldClearMessageStatus) {
          txn31.setRequiredInfoMessage(null);
        }
        txn31Store.save(txn31);
        break;
    }

    updateMetrics(txn);
  }

  protected boolean areFundsReceived(JdbcSepTransaction txn) {
    return txn.getTransferReceivedAt() != null;
  }

  protected boolean isErrorStatus(SepTransactionStatus status) {
    return Set.of(EXPIRED, ERROR).contains(status);
  }

  protected boolean isFinalStatus(SepTransactionStatus status) {
    return Set.of(COMPLETED, REFUNDED).contains(status);
  }

  private void updateMetrics(JdbcSepTransaction txn) {
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        metricsService.counter(PLATFORM_RPC_TRANSACTION, SEP, TV_SEP6).increment();
        break;
      case SEP_24:
        metricsService.counter(PLATFORM_RPC_TRANSACTION, SEP, TV_SEP24).increment();
        break;
      case SEP_31:
        metricsService.counter(PLATFORM_RPC_TRANSACTION, SEP, TV_SEP31).increment();
        break;
    }
  }
}
