package org.stellar.anchor.platform.rpc;

import static java.util.Collections.emptySet;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Sep.*;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_CUSTOMER_INFO_UPDATED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.*;

import java.util.Set;
import java.util.UUID;
import org.stellar.anchor.api.callback.CustomerIntegration;
import org.stellar.anchor.api.callback.GetCustomerRequest;
import org.stellar.anchor.api.callback.GetCustomerResponse;
import org.stellar.anchor.api.event.AnchorEvent;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.NotifyCustomerInfoUpdatedRequest;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.sep.sep12.Sep12Status;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;

public class NotifyCustomerInfoUpdatedHandler
    extends RpcMethodHandler<NotifyCustomerInfoUpdatedRequest> {
  private final CustomerIntegration customerIntegration;
  private final EventService.Session eventSession;

  public NotifyCustomerInfoUpdatedHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      CustomerIntegration customerIntegration,
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
        NotifyCustomerInfoUpdatedRequest.class);
    this.customerIntegration = customerIntegration;
    this.eventSession =
        eventService.createSession(this.getClass().getName(), EventService.EventQueue.TRANSACTION);
  }

  @Override
  protected void validate(JdbcSepTransaction txn, NotifyCustomerInfoUpdatedRequest request)
      throws InvalidRequestException, InvalidParamsException, BadRequestException {
    super.validate(txn, request);
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_CUSTOMER_INFO_UPDATED;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyCustomerInfoUpdatedRequest request) throws AnchorException {
    String status = null;
    if (request.getCustomerId() != null) {
      GetCustomerResponse customer =
          customerIntegration.getCustomer(
              GetCustomerRequest.builder()
                  .transactionId(txn.getId())
                  .id(request.getCustomerId())
                  .type(request.getCustomerType())
                  .build());
      status = customer.getStatus();
      eventSession.publish(
          AnchorEvent.builder()
              .id(UUID.randomUUID().toString())
              .sep(SEP_12.getSep().toString())
              .type(AnchorEvent.Type.CUSTOMER_UPDATED)
              .customer(GetCustomerResponse.to(customer))
              .build());
    }

    if (SEP_6 == Sep.from(txn.getProtocol())) {
      if (status == null) {
        return PENDING_ANCHOR;
      }
      switch (Sep12Status.valueOf(status)) {
        case ACCEPTED, PROCESSING:
          return PENDING_ANCHOR;
        case NEEDS_INFO:
          return PENDING_CUSTOMER_INFO_UPDATE;
        case REJECTED:
          return ERROR;
      }
    }
    if (SEP_31 == Sep.from(txn.getProtocol())) {
      if (status == null) {
        return PENDING_RECEIVER;
      }
      switch (Sep12Status.valueOf(status)) {
        case ACCEPTED, PROCESSING:
          return PENDING_RECEIVER;
        case NEEDS_INFO:
          return PENDING_CUSTOMER_INFO_UPDATE;
        case REJECTED:
          return ERROR;
      }
    }
    throw new InvalidRequestException(
        String.format(
            "RPC method[%s] is not supported for protocol[%s]", getRpcMethod(), txn.getProtocol()));
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        return Set.of(INCOMPLETE, PENDING_ANCHOR, PENDING_CUSTOMER_INFO_UPDATE);
      case SEP_31:
        return Set.of(PENDING_RECEIVER, PENDING_CUSTOMER_INFO_UPDATE);
      default:
        return emptySet();
    }
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyCustomerInfoUpdatedRequest request) {}
}
