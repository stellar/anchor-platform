package org.stellar.anchor.platform.rpc;

import static java.util.Collections.emptySet;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_INTERACTIVE_FLOW_COMPLETED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.INCOMPLETE;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;

import java.util.Set;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.AmountAssetRequest;
import org.stellar.anchor.api.rpc.method.NotifyInteractiveFlowCompletedRequest;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.utils.AssetValidationUtils;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;

public class NotifyInteractiveFlowCompletedHandler
    extends RpcMethodHandler<NotifyInteractiveFlowCompletedRequest> {

  public NotifyInteractiveFlowCompletedHandler(
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
        NotifyInteractiveFlowCompletedRequest.class);
  }

  @Override
  protected void validate(JdbcSepTransaction txn, NotifyInteractiveFlowCompletedRequest request)
      throws BadRequestException, InvalidParamsException, InvalidRequestException {
    super.validate(txn, request);

    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;

    AssetValidationUtils.validateAssetAmount("amount_in", request.getAmountIn(), assetService);
    switch (Kind.from(txn24.getKind())) {
      case DEPOSIT:
        if (AssetValidationUtils.isStellarAsset(request.getAmountIn().getAsset())) {
          throw new InvalidParamsException("amount_in.asset should be non-stellar asset");
        }
        break;
      case WITHDRAWAL:
        if (!AssetValidationUtils.isStellarAsset(request.getAmountIn().getAsset())) {
          throw new InvalidParamsException("amount_in.asset should be stellar asset");
        }
        break;
    }

    AssetValidationUtils.validateAssetAmount("amount_out", request.getAmountOut(), assetService);
    switch (Kind.from(txn24.getKind())) {
      case DEPOSIT:
        if (!AssetValidationUtils.isStellarAsset(request.getAmountOut().getAsset())) {
          throw new InvalidParamsException("amount_out.asset should be stellar asset");
        }
        break;
      case WITHDRAWAL:
        if (AssetValidationUtils.isStellarAsset(request.getAmountOut().getAsset())) {
          throw new InvalidParamsException("amount_out.asset should be non-stellar asset");
        }
        break;
    }

    if ((request.getAmountFee() == null && request.getFeeDetails() == null)
        || (request.getAmountFee() != null && request.getFeeDetails() != null)) {
      throw new InvalidParamsException("Either amount_fee or fee_details must be set");
    }

    if (request.getAmountFee() != null) {
      AssetValidationUtils.validateAssetAmount(
          "amount_fee", request.getAmountFee(), true, assetService);
    }
    if (request.getFeeDetails() != null) {
      AssetValidationUtils.validateFeeDetails(request.getFeeDetails(), txn, assetService);
    }
    String feeAsset =
        request.getFeeDetails() != null
            ? request.getFeeDetails().getAsset()
            : request.getAmountFee().getAsset();
    switch (Kind.from(txn24.getKind())) {
      case DEPOSIT:
        if (AssetValidationUtils.isStellarAsset(feeAsset)) {
          throw new InvalidParamsException("fee asset should be a non-stellar asset");
        }
        break;
      case WITHDRAWAL:
        if (!AssetValidationUtils.isStellarAsset(feeAsset)) {
          throw new InvalidParamsException("fee asset should be a stellar asset");
        }
        break;
    }

    if (request.getAmountExpected() != null) {
      AssetValidationUtils.validateAssetAmount(
          "amount_expected",
          AmountAssetRequest.builder()
              .amount(request.getAmountExpected().getAmount())
              .asset(request.getAmountIn().getAsset())
              .build(),
          assetService);
    }
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_INTERACTIVE_FLOW_COMPLETED;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyInteractiveFlowCompletedRequest request) {
    return PENDING_ANCHOR;
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    if (SEP_24 == Sep.from(txn.getProtocol())) {
      return Set.of(INCOMPLETE);
    }
    return emptySet();
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyInteractiveFlowCompletedRequest request) {
    JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;

    txn24.setAmountIn(request.getAmountIn().getAmount());
    txn24.setAmountInAsset(request.getAmountIn().getAsset());

    txn24.setAmountOut(request.getAmountOut().getAmount());
    txn24.setAmountOutAsset(request.getAmountOut().getAsset());

    if (request.getAmountFee() != null) {
      txn24.setAmountFee(request.getAmountFee().getAmount());
      txn24.setAmountFeeAsset(request.getAmountFee().getAsset());
    } else {
      txn24.setAmountFee(request.getFeeDetails().getTotal());
      txn24.setAmountFeeAsset(request.getFeeDetails().getAsset());
      txn24.setFeeDetailsList(request.getFeeDetails().getDetails());
    }

    if (request.getAmountExpected() != null) {
      txn24.setAmountExpected(request.getAmountExpected().getAmount());
    } else {
      txn24.setAmountExpected(txn.getAmountIn());
    }
  }
}
