package org.stellar.anchor.platform.rpc;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.*;
import static org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_REFUND_SENT;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_EXTERNAL;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_RECEIVER;
import static org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_STELLAR;
import static org.stellar.anchor.api.sep.SepTransactionStatus.REFUNDED;
import static org.stellar.anchor.api.shared.RefundPayment.IdType.*;
import static org.stellar.anchor.util.AssetHelper.getAssetCode;
import static org.stellar.anchor.util.MathHelper.*;
import static org.stellar.anchor.util.MathHelper.formatAmount;

import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.*;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.api.exception.rpc.InvalidRequestException;
import org.stellar.anchor.api.platform.PlatformTransactionData;
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind;
import org.stellar.anchor.api.rpc.method.AmountAssetRequest;
import org.stellar.anchor.api.rpc.method.NotifyRefundSentRequest;
import org.stellar.anchor.api.rpc.method.RpcMethod;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.shared.Amount;
import org.stellar.anchor.api.shared.Refunds;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.event.EventService;
import org.stellar.anchor.metrics.MetricsService;
import org.stellar.anchor.platform.data.*;
import org.stellar.anchor.platform.utils.AssetValidationUtils;
import org.stellar.anchor.platform.validator.RequestValidator;
import org.stellar.anchor.sep24.Sep24RefundPayment;
import org.stellar.anchor.sep24.Sep24Refunds;
import org.stellar.anchor.sep24.Sep24TransactionStore;
import org.stellar.anchor.sep31.RefundPayment;
import org.stellar.anchor.sep31.Sep31Refunds;
import org.stellar.anchor.sep31.Sep31TransactionStore;
import org.stellar.anchor.sep6.Sep6TransactionStore;

public class NotifyRefundSentHandler extends RpcMethodHandler<NotifyRefundSentRequest> {

  public NotifyRefundSentHandler(
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
        NotifyRefundSentRequest.class);
  }

  @Override
  protected void validate(JdbcSepTransaction txn, NotifyRefundSentRequest request)
      throws InvalidParamsException, InvalidRequestException, BadRequestException {
    super.validate(txn, request);

    SepTransactionStatus currentStatus = SepTransactionStatus.from(txn.getStatus());
    switch (PlatformTransactionData.Sep.from(txn.getProtocol())) {
      case SEP_6:
      case SEP_24:
        if (request.getRefund() == null && PENDING_ANCHOR == currentStatus) {
          throw new InvalidParamsException("refund is required");
        }
        break;
      case SEP_31:
        JdbcSep31Transaction txn31 = (JdbcSep31Transaction) txn;
        Sep31Refunds sep31Refunds = txn31.getRefunds();
        if (request.getRefund() == null) {
          throw new InvalidParamsException("refund is required");
        }

        if (PENDING_RECEIVER == currentStatus) {
          if (sep31Refunds != null
              && sep31Refunds.getRefundPayments() != null
              && !sep31Refunds.getRefundPayments().isEmpty()) {
            throw new InvalidRequestException(
                String.format(
                    "Multiple refunds aren't supported for kind[%s], protocol[%s] and action[%s]",
                    RECEIVE, txn.getProtocol(), getRpcMethod()));
          }
        }
    }

    if (request.getRefund() != null) {
      AssetValidationUtils.validateAssetAmount(
          "refund.amount",
          AmountAssetRequest.builder()
              .amount(request.getRefund().getAmount().getAmount())
              .asset(txn.getAmountInAsset())
              .build(),
          assetService);
      AssetValidationUtils.validateAssetAmount(
          "refund.amountFee",
          AmountAssetRequest.builder()
              .amount(request.getRefund().getAmountFee().getAmount())
              .asset(txn.getAmountInAsset())
              .build(),
          true,
          assetService);

      if (!txn.getAmountInAsset().equals(request.getRefund().getAmount().getAsset())) {
        throw new InvalidParamsException(
            "refund.amount.asset does not match transaction amount_in_asset");
      }
      if (!txn.getAmountFeeAsset().equals(request.getRefund().getAmountFee().getAsset())) {
        throw new InvalidParamsException(
            "refund.amount_fee.asset does not match match transaction amount_fee_asset");
      }
    }
  }

  @Override
  public RpcMethod getRpcMethod() {
    return NOTIFY_REFUND_SENT;
  }

  @Override
  protected SepTransactionStatus getNextStatus(
      JdbcSepTransaction txn, NotifyRefundSentRequest request)
      throws InvalidParamsException, InvalidRequestException {
    AssetInfo assetInfo = assetService.getAsset(getAssetCode(txn.getAmountInAsset()));
    NotifyRefundSentRequest.Refund refund = request.getRefund();

    BigDecimal totalRefunded;
    switch (PlatformTransactionData.Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        Refunds refunds = txn6.getRefunds();

        if (refunds == null || refunds.getPayments() == null) {
          totalRefunded =
              sum(assetInfo, refund.getAmount().getAmount(), refund.getAmountFee().getAmount());
        } else {
          if (PENDING_ANCHOR == SepTransactionStatus.from(txn.getStatus())) {
            totalRefunded =
                sum(
                    assetInfo,
                    refunds.getAmountRefunded().getAmount(),
                    refund.getAmount().getAmount(),
                    refund.getAmountFee().getAmount());
          } else {
            if (refund == null) {
              totalRefunded = decimal(refunds.getAmountRefunded().getAmount(), assetInfo);
            } else {
              org.stellar.anchor.api.shared.RefundPayment[] payments = refunds.getPayments();

              // make sure refund, provided in request, was sent on refund_pending
              Arrays.stream(payments)
                  .map(org.stellar.anchor.api.shared.RefundPayment::getId)
                  .filter(id -> id.equals(refund.getId()))
                  .findFirst()
                  .orElseThrow(() -> new InvalidParamsException("Invalid refund id"));

              totalRefunded =
                  Arrays.stream(payments)
                      .map(
                          payment -> {
                            if (payment.getId().equals(request.getRefund().getId())) {
                              return sum(
                                  assetInfo,
                                  refund.getAmount().getAmount(),
                                  refund.getAmountFee().getAmount());
                            } else {
                              return sum(
                                  assetInfo,
                                  payment.getAmount().getAmount(),
                                  payment.getFee().getAmount());
                            }
                          })
                      .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
          }
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        Sep24Refunds sep24Refunds = txn24.getRefunds();

        if (sep24Refunds == null || sep24Refunds.getRefundPayments() == null) {
          totalRefunded =
              sum(assetInfo, refund.getAmount().getAmount(), refund.getAmountFee().getAmount());
        } else {
          if (PENDING_ANCHOR == SepTransactionStatus.from(txn.getStatus())) {
            totalRefunded =
                sum(
                    assetInfo,
                    sep24Refunds.getAmountRefunded(),
                    refund.getAmount().getAmount(),
                    refund.getAmountFee().getAmount());
          } else {
            if (refund == null) {
              totalRefunded = decimal(sep24Refunds.getAmountRefunded(), assetInfo);
            } else {
              List<Sep24RefundPayment> payments = sep24Refunds.getRefundPayments();

              // make sure refund, provided in request, was sent on refund_pending
              payments.stream()
                  .map(Sep24RefundPayment::getId)
                  .filter(id -> id.equals(refund.getId()))
                  .findFirst()
                  .orElseThrow(() -> new InvalidParamsException("Invalid refund id"));

              totalRefunded =
                  payments.stream()
                      .map(
                          payment -> {
                            if (payment.getId().equals(request.getRefund().getId())) {
                              return sum(
                                  assetInfo,
                                  refund.getAmount().getAmount(),
                                  refund.getAmountFee().getAmount());
                            } else {
                              return sum(assetInfo, payment.getAmount(), payment.getFee());
                            }
                          })
                      .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
          }
        }
        break;
      case SEP_31:
        totalRefunded =
            sum(assetInfo, refund.getAmount().getAmount(), refund.getAmountFee().getAmount());
        break;
      default:
        throw new InvalidRequestException(
            String.format(
                "RPC method[%s] is not supported for protocol[%s]",
                getRpcMethod(), txn.getProtocol()));
    }

    BigDecimal amountIn = decimal(txn.getAmountIn(), assetInfo);
    if (totalRefunded.compareTo(amountIn) == 0) {
      return REFUNDED;
    } else if (totalRefunded.compareTo(amountIn) < 0) {
      return PENDING_ANCHOR;
    } else {
      throw new InvalidParamsException("Refund amount exceeds amount_in");
    }
  }

  @Override
  protected Set<SepTransactionStatus> getSupportedStatuses(JdbcSepTransaction txn) {
    Set<SepTransactionStatus> supportedStatuses = new HashSet<>();

    switch (PlatformTransactionData.Sep.from(txn.getProtocol())) {
      case SEP_6:
        JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
        switch (Kind.from(txn6.getKind())) {
          case DEPOSIT:
          case DEPOSIT_EXCHANGE:
            if (areFundsReceived(txn6)) {
              supportedStatuses.add(PENDING_EXTERNAL);
              supportedStatuses.add(PENDING_ANCHOR);
            }
            break;
          case WITHDRAWAL:
          case WITHDRAWAL_EXCHANGE:
            supportedStatuses.add(PENDING_STELLAR);
            if (areFundsReceived(txn6)) {
              supportedStatuses.add(PENDING_ANCHOR);
            }
            break;
        }
        break;
      case SEP_24:
        JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
        switch (Kind.from(txn24.getKind())) {
          case DEPOSIT:
            if (areFundsReceived(txn24)) {
              supportedStatuses.add(PENDING_EXTERNAL);
              supportedStatuses.add(PENDING_ANCHOR);
            }
            break;
          case WITHDRAWAL:
            supportedStatuses.add(PENDING_STELLAR);
            if (areFundsReceived(txn24)) {
              supportedStatuses.add(PENDING_ANCHOR);
            }
            break;
        }
        break;
      case SEP_31:
        supportedStatuses.add(PENDING_STELLAR);
        supportedStatuses.add(PENDING_RECEIVER);
    }

    return supportedStatuses;
  }

  @Override
  protected void updateTransactionWithRpcRequest(
      JdbcSepTransaction txn, NotifyRefundSentRequest request) {
    AssetInfo assetInfo = assetService.getAsset(getAssetCode(txn.getAmountInAsset()));
    NotifyRefundSentRequest.Refund requestRefund = request.getRefund();
    if (requestRefund != null) {
      switch (PlatformTransactionData.Sep.from(txn.getProtocol())) {
        case SEP_6:
          JdbcSep6Transaction txn6 = (JdbcSep6Transaction) txn;
          boolean isDeposit =
              ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(Kind.from(txn6.getKind()));
          org.stellar.anchor.api.shared.RefundPayment refundPayment =
              org.stellar.anchor.api.shared.RefundPayment.builder()
                  .id(requestRefund.getId())
                  .idType(isDeposit ? EXTERNAL : STELLAR)
                  .amount(
                      Amount.builder()
                          .asset(requestRefund.getAmount().getAsset())
                          .amount(requestRefund.getAmount().getAmount())
                          .build())
                  .fee(
                      Amount.builder()
                          .asset(requestRefund.getAmountFee().getAsset())
                          .amount(requestRefund.getAmountFee().getAmount())
                          .build())
                  .build();

          Refunds refunds = txn6.getRefunds();
          if (refunds == null) {
            refunds = new Refunds();
          }

          if (refunds.getPayments() == null) {
            refunds.setPayments(new org.stellar.anchor.api.shared.RefundPayment[] {refundPayment});
          } else {
            List<org.stellar.anchor.api.shared.RefundPayment> payments =
                new ArrayList<>(Arrays.asList(refunds.getPayments()));
            payments.removeIf(payment -> payment.getId().equals(request.getRefund().getId()));
            payments.add(refundPayment);
            refunds.setPayments(
                payments.toArray(new org.stellar.anchor.api.shared.RefundPayment[0]));
          }

          List<org.stellar.anchor.api.shared.RefundPayment> refundPayments =
              new ArrayList<>(Arrays.asList(refunds.getPayments()));

          // Calculate the total fee amount by summing together fees from all refund payments.
          refunds.setAmountFee(
              new Amount(
                  formatAmount(
                      refundPayments.stream()
                          .map(org.stellar.anchor.api.shared.RefundPayment::getFee)
                          .map(amount -> decimal(amount.getAmount(), assetInfo))
                          .reduce(BigDecimal.ZERO, BigDecimal::add)),
                  requestRefund.getAmountFee().getAsset()));

          // Calculate the total refunded amount by summing together amounts from all refund
          // payments.
          refunds.setAmountRefunded(
              new Amount(
                  formatAmount(
                      sum(
                          assetInfo,
                          refunds.getAmountFee().getAmount(),
                          formatAmount(
                              refundPayments.stream()
                                  .map(org.stellar.anchor.api.shared.RefundPayment::getAmount)
                                  .map(amount -> decimal(amount.getAmount(), assetInfo))
                                  .reduce(BigDecimal.ZERO, BigDecimal::add)))),
                  requestRefund.getAmount().getAsset()));

          txn6.setRefunds(refunds);
          break;
        case SEP_24:
          JdbcSep24Transaction txn24 = (JdbcSep24Transaction) txn;
          boolean isTxn24Deposit =
              ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(Kind.from(txn24.getKind()));
          Sep24RefundPayment sep24RefundPayment =
              JdbcSep24RefundPayment.builder()
                  .id(requestRefund.getId())
                  .idType(isTxn24Deposit ? EXTERNAL.toString() : STELLAR.toString())
                  .amount(requestRefund.getAmount().getAmount())
                  .fee(requestRefund.getAmountFee().getAmount())
                  .build();

          Sep24Refunds sep24Refunds = txn24.getRefunds();
          if (sep24Refunds == null) {
            sep24Refunds = new JdbcSep24Refunds();
          }

          if (sep24Refunds.getRefundPayments() == null) {
            sep24Refunds.setRefundPayments(List.of(sep24RefundPayment));
          } else {
            List<Sep24RefundPayment> payments = sep24Refunds.getRefundPayments();
            payments.removeIf(payment -> payment.getId().equals(request.getRefund().getId()));
            payments.add(sep24RefundPayment);
            sep24Refunds.setRefundPayments(payments);
          }

          sep24Refunds.recalculateAmounts(assetInfo);
          txn24.setRefunds(sep24Refunds);
          break;
        case SEP_31:
          RefundPayment sep31RefundPayment =
              JdbcSep31RefundPayment.builder()
                  .id(requestRefund.getId())
                  .amount(requestRefund.getAmount().getAmount())
                  .fee(requestRefund.getAmountFee().getAmount())
                  .build();

          JdbcSep31Transaction txn31 = (JdbcSep31Transaction) txn;
          Sep31Refunds sep31Refunds = new JdbcSep31Refunds();
          sep31Refunds.setRefundPayments(List.of(sep31RefundPayment));

          sep31Refunds.recalculateAmounts(assetInfo);
          txn31.setRefunds(sep31Refunds);
          break;
      }
    }
  }
}
