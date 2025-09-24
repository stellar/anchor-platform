package org.stellar.anchor.platform.rpc;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT;
import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT_EXCHANGE;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_OFFCHAIN_FUNDS_RECEIVED;
import static org.stellar.anchor.api.sep.SepTransactionStatus.*;

import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep;
import org.stellar.anchor.api.rpc.method.AmountAssetRequest;
import org.stellar.anchor.api.rpc.method.NotifyOffchainFundsReceivedRequest;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.config.CustodyConfig;
import org.stellar.anchor.custody.CustodyService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.platform.utils.AssetValidationUtils;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;

public class NotifyOffchainFundsReceivedHandler
    extends RpcMethodHandler<NotifyOffchainFundsReceivedRequest> {

  private final CustodyService custodyService;
  private final CustodyConfig custodyConfig;

  public NotifyOffchainFundsReceivedHandler(
      Sep6TransactionStore txn6Store,
      Sep24TransactionStore txn24Store,
      Sep31TransactionStore txn31Store,
      RequestValidator requestValidator,
      AssetService assetService,
      CustodyService custodyService,
      CustodyConfig custodyConfig,
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
        NotifyOffchainFundsReceivedRequest.class);
    this.custodyService = custodyService;
    this.custodyConfig = custodyConfig;
  }

  @Override
  protected void validate(JdbcSepTransaction txn, NotifyOffchainFundsReceivedRequest request)
      throws InvalidParamsException, InvalidRequestException, BadRequestException {
    super.validate(txn, request);

    // If none of the accepted combinations of input parameters satisfies -> throw an exception
    if (!
    // None of the amounts are provided
    ((request.getAmountIn() == null
            && request.getAmountOut() == null
            && request.getAmountFee() == null
            && request.getFeeDetails() == null)
        ||
        // All the amounts are provided (allow either amount_fee or fee_details)
        (request.getAmountIn() != null
            && request.getAmountOut() != null
            && (request.getAmountFee() != null || request.getFeeDetails() != null))
        ||
        // Only amount_in is provided
        (request.getAmountIn() != null
            && request.getAmountOut() == null
            && request.getAmountFee() == null
            && request.getFeeDetails() == null))) {
      throw new InvalidParamsException(
          "Invalid amounts combination provided: all, none or only amount_in should be set");
    }

    // In case 2nd predicate in previous IF statement was TRUE
    if (request.getAmountFee() != null && request.getFeeDetails() != null) {
      throw new InvalidParamsException("Either amount_fee or fee_details should be set");
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
    if (request.getAmountFee() != null) {
      AssetValidationUtils.validateAssetAmount(
          "amount_fee",
          AmountAssetRequest.builder()
              .amount(request.getAmountFee().getAmount())
              .asset(txn.getAmountFeeAsset())
              .build(),
          true,
          assetService);
    }
    if (request.getFeeDetails() != null) {
      AssetValidationUtils.validateFeeDetails(request.getFeeDetails(), txn, assetService);
    }
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_OFFCHAIN_FUNDS_RECEIVED;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyOffchainFundsReceivedRequest request)
      throws InvalidRequestException {
    return PENDING_ANCHOR;
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    Set<SepTransactionStatus> supportedStatuses = new HashSet<>();
    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(Kind.from(txn6.getKind()))) {
          supportedStatuses.add(PENDING_USR_TRANSFER_START);
          supportedStatuses.add(ON_HOLD);
          supportedStatuses.add(PENDING_EXTERNAL);
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (DEPOSIT == Kind.from(txn24.getKind())) {
          supportedStatuses.add(PENDING_USR_TRANSFER_START);
          supportedStatuses.add(ON_HOLD);
          supportedStatuses.add(PENDING_EXTERNAL);
        }
        break;
      default:
        break;
    }
    return supportedStatuses;
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyOffchainFundsReceivedRequest request) throws AnchorException {
    if (request.getExternalTransactionId() != null) {
      txn.setExternalTransactionId(request.getExternalTransactionId());
      if (request.getFundsReceivedAt() != null) {
        txn.setTransferReceivedAt(request.getFundsReceivedAt());
      }
    }
    if (txn.getTransferReceivedAt() == null) {
      txn.setTransferReceivedAt(Instant.now());
    }

    if (request.getAmountIn() != null) {
      txn.setAmountIn(request.getAmountIn().getAmount());
    }
    if (request.getAmountOut() != null) {
      txn.setAmountOut(request.getAmountOut().getAmount());
    }
    if (request.getAmountFee() != null) {
      txn.setAmountFee(request.getAmountFee().getAmount());
    }
    if (request.getFeeDetails() != null) {
      txn.setAmountFee(request.getFeeDetails().getTotal());
      txn.setFeeDetailsList(request.getFeeDetails().getDetails());
    }

    switch (Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        if (custodyConfig.isCustodyIntegrationEnabled()) {
          custodyService.createTransaction(txn6);
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        if (custodyConfig.isCustodyIntegrationEnabled()) {
          custodyService.createTransaction(txn24);
        }
        break;
      default:
        break;
    }
  }
}
