package org.stellar.anchor.util;

import static org.stellar.anchor.api.platform.PlatformTransactionData.Kind.*;

import com.google.common.collect.ImmutableSet;
import jakarta.annotation.Nullable;
import java.util.Optional;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest;
import org.stellar.anchor.api.platform.GetTransactionResponse;
import org.stellar.anchor.api.platform.PlatformTransactionData;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.api.shared.*;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.sep24.Sep24RefundPayment;
import org.stellar.anchor.sep24.Sep24Refunds;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.sep31.Sep31Refunds;
import org.stellar.anchor.sep31.Sep31Transaction;
import org.stellar.anchor.sep6.Sep6Transaction;

public class TransactionMapper {

  public static CreateCustodyTransactionRequest toCustodyTransaction(Sep6Transaction txn) {
    PlatformTransactionData.Kind kind = PlatformTransactionData.Kind.from(txn.getKind());
    return CreateCustodyTransactionRequest.builder()
        .id(txn.getId())
        .memo(txn.getMemo())
        .memoType(txn.getMemoType())
        .protocol("6")
        .fromAccount(
            ImmutableSet.of(WITHDRAWAL, WITHDRAWAL_EXCHANGE).contains(kind)
                ? txn.getFromAccount()
                : null)
        .toAccount(
            ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(kind)
                ? txn.getToAccount()
                : txn.getWithdrawAnchorAccount())
        .amount(
            ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(kind)
                ? txn.getAmountOut()
                : Optional.ofNullable(txn.getAmountExpected()).orElse(txn.getAmountIn()))
        .asset(
            ImmutableSet.of(DEPOSIT, DEPOSIT_EXCHANGE).contains(kind)
                ? txn.getAmountOutAsset()
                : txn.getAmountInAsset())
        .kind(txn.getKind())
        .build();
  }

  public static CreateCustodyTransactionRequest toCustodyTransaction(Sep24Transaction txn) {
    return CreateCustodyTransactionRequest.builder()
        .id(txn.getId())
        .memo(txn.getMemo())
        .memoType(txn.getMemoType())
        .protocol("24")
        .fromAccount(WITHDRAWAL.getKind().equals(txn.getKind()) ? txn.getFromAccount() : null)
        .toAccount(
            DEPOSIT.getKind().equals(txn.getKind())
                ? txn.getToAccount()
                : txn.getWithdrawAnchorAccount())
        .amount(
            DEPOSIT.getKind().equals(txn.getKind())
                ? txn.getAmountOut()
                : Optional.ofNullable(txn.getAmountExpected()).orElse(txn.getAmountIn()))
        .asset(
            DEPOSIT.getKind().equals(txn.getKind())
                ? txn.getAmountOutAsset()
                : txn.getAmountInAsset())
        .kind(txn.getKind())
        .build();
  }

  public static CreateCustodyTransactionRequest toCustodyTransaction(Sep31Transaction txn) {
    return CreateCustodyTransactionRequest.builder()
        .id(txn.getId())
        .memo(txn.getStellarMemo())
        .memoType(txn.getStellarMemoType())
        .protocol("31")
        .toAccount(txn.getToAccount())
        .amount(txn.getAmountIn())
        .asset(txn.getAmountInAsset())
        .kind(RECEIVE.getKind())
        .build();
  }

  public static GetTransactionResponse toGetTransactionResponse(Sep31Transaction txn) {
    Refunds refunds = null;
    if (txn.getRefunds() != null) {
      refunds = toRefunds(txn.getRefunds(), txn.getAmountInAsset());
    }

    return GetTransactionResponse.builder()
        .id(txn.getId())
        .sep(PlatformTransactionData.Sep.SEP_31)
        .kind(RECEIVE)
        .status(SepTransactionStatus.from(txn.getStatus()))
        .fundingMethod(txn.getFundingMethod())
        .amountExpected(new Amount(txn.getAmountExpected(), txn.getAmountInAsset()))
        .amountIn(new Amount(txn.getAmountIn(), txn.getAmountInAsset()))
        .amountOut(new Amount(txn.getAmountOut(), txn.getAmountOutAsset()))
        .feeDetails(txn.getFeeDetails())
        .quoteId(txn.getQuoteId())
        .startedAt(txn.getStartedAt())
        .updatedAt(txn.getUpdatedAt())
        .completedAt(txn.getCompletedAt())
        .userActionRequiredBy(txn.getUserActionRequiredBy())
        .transferReceivedAt(txn.getTransferReceivedAt())
        .message(txn.getRequiredInfoMessage()) // Assuming these are meant to be the same.
        .refunds(refunds)
        .stellarTransactions(txn.getStellarTransactions())
        .sourceAccount(txn.getFromAccount())
        .destinationAccount(txn.getToAccount())
        .externalTransactionId(txn.getExternalTransactionId())
        .memo(txn.getStellarMemo())
        .memoType(txn.getStellarMemoType())
        .clientDomain(txn.getClientDomain())
        .clientName(txn.getClientName())
        // TODO: SEP-31 supports refund memo but we don't use it
        .refundMemo(txn.getStellarMemo())
        .refundMemoType(txn.getStellarMemoType())
        .customers(txn.getCustomers())
        .creator(txn.getCreator())
        .build();
  }

  public static GetTransactionResponse toGetTransactionResponse(
      Sep6Transaction txn, AssetService assetService) {
    String amountInAsset = makeAsset(txn.getAmountInAsset(), assetService, txn);
    String amountOutAsset = makeAsset(txn.getAmountOutAsset(), assetService, txn);
    String amountExpectedAsset = makeAsset(null, assetService, txn);
    StellarId customer =
        StellarId.builder()
            .account(txn.getWebAuthAccount())
            .memo(txn.getWebAuthAccountMemo())
            .build();

    return GetTransactionResponse.builder()
        .id(txn.getId())
        .sep(PlatformTransactionData.Sep.SEP_6)
        .kind(PlatformTransactionData.Kind.from(txn.getKind()))
        .status(SepTransactionStatus.from(txn.getStatus()))
        .fundingMethod(txn.getType())
        .type(txn.getType())
        .amountExpected(
            (amountExpectedAsset != null)
                ? new Amount(txn.getAmountExpected(), amountExpectedAsset)
                : null)
        .amountIn(
            (amountInAsset != null && txn.getAmountIn() != null)
                ? Amount.create(txn.getAmountIn(), amountInAsset)
                : null)
        .amountOut(
            (amountOutAsset != null && txn.getAmountOut() != null)
                ? Amount.create(txn.getAmountOut(), amountOutAsset)
                : null)
        .feeDetails(txn.getFeeDetails())
        .quoteId(txn.getQuoteId())
        .startedAt(txn.getStartedAt())
        .updatedAt(txn.getUpdatedAt())
        .completedAt(txn.getCompletedAt())
        .userActionRequiredBy(txn.getUserActionRequiredBy())
        .transferReceivedAt(txn.getTransferReceivedAt())
        .message(txn.getMessage())
        .refunds(txn.getRefunds())
        .stellarTransactions(txn.getStellarTransactions())
        .sourceAccount(txn.getFromAccount())
        .destinationAccount(txn.getToAccount())
        .externalTransactionId(txn.getExternalTransactionId())
        .memo(txn.getMemo())
        .memoType(txn.getMemoType())
        .clientDomain(txn.getClientDomain())
        .clientName(txn.getClientName())
        .refundMemo(txn.getRefundMemo())
        .refundMemoType(txn.getRefundMemoType())
        .customers(Customers.builder().sender(customer).receiver(customer).build())
        .creator(
            StellarId.builder()
                .account(txn.getWebAuthAccount())
                .memo(txn.getWebAuthAccountMemo())
                .build())
        .build();
  }

  public static GetTransactionResponse toGetTransactionResponse(
      Sep24Transaction txn, AssetService assetService) {
    Refunds refunds = null;
    if (txn.getRefunds() != null) {
      refunds = toRefunds(txn.getRefunds(), txn.getAmountInAsset());
    }

    String amountInAsset = makeAsset(txn.getAmountInAsset(), assetService, txn);
    String amountOutAsset = makeAsset(txn.getAmountOutAsset(), assetService, txn);
    String amountExpectedAsset = makeAsset(null, assetService, txn);

    StellarId customer =
        StellarId.builder()
            .account(txn.getWebAuthAccount())
            .memo(txn.getWebAuthAccountMemo())
            .build();
    String sourceAccount = txn.getFromAccount();

    return GetTransactionResponse.builder()
        .id(txn.getId())
        .sep(PlatformTransactionData.Sep.SEP_24)
        .kind(from(txn.getKind()))
        .status(SepTransactionStatus.from(txn.getStatus()))
        .amountExpected(
            (amountExpectedAsset != null)
                ? new Amount(txn.getAmountExpected(), amountExpectedAsset)
                : null)
        .amountIn(
            (amountInAsset != null && txn.getAmountIn() != null)
                ? Amount.create(txn.getAmountIn(), amountInAsset)
                : null)
        .amountOut(
            (amountOutAsset != null && txn.getAmountOut() != null)
                ? Amount.create(txn.getAmountOut(), amountOutAsset)
                : null)
        .feeDetails(txn.getFeeDetails())
        .quoteId(txn.getQuoteId())
        .startedAt(txn.getStartedAt())
        .updatedAt(txn.getUpdatedAt())
        .completedAt(txn.getCompletedAt())
        .userActionRequiredBy(txn.getUserActionRequiredBy())
        .message(txn.getMessage())
        .refunds(refunds)
        .stellarTransactions(txn.getStellarTransactions())
        // constructor is used because AMOUNT can be null, when ASSET is always non-null
        .sourceAccount(sourceAccount)
        .destinationAccount(txn.getToAccount())
        .externalTransactionId(txn.getExternalTransactionId())
        .memo(txn.getMemo())
        .memoType(txn.getMemoType())
        .clientDomain(txn.getClientDomain())
        .clientName(txn.getClientName())
        .refundMemo(txn.getRefundMemo())
        .refundMemoType(txn.getRefundMemoType())
        .customers(Customers.builder().sender(customer).receiver(customer).build())
        .creator(
            StellarId.builder()
                .account(txn.getWebAuthAccount())
                .memo(txn.getWebAuthAccountMemo())
                .build())
        .quoteId(txn.getQuoteId())
        .build();
  }

  // TODO: make this a static helper method
  private static String makeAsset(
      @Nullable String dbAsset, AssetService service, Sep24Transaction txn) {
    if (dbAsset != null) {
      return dbAsset;
    }

    AssetInfo info = service.getAsset(txn.getRequestAssetCode(), txn.getRequestAssetIssuer());

    // Already validated in the interactive flow
    return (info != null) ? info.getId() : null;
  }

  private static String makeAsset(
      @Nullable String dbAsset, AssetService service, Sep6Transaction txn) {
    if (dbAsset != null) {
      return dbAsset;
    }

    AssetInfo info = service.getAsset(txn.getRequestAssetCode(), txn.getRequestAssetIssuer());
    if (info == null) {
      return null;
    }

    return info.getId();
  }

  static RefundPayment toRefundPayment(Sep24RefundPayment refundPayment, String assetName) {
    return RefundPayment.builder()
        .id(refundPayment.getId())
        .idType(RefundPayment.IdType.STELLAR)
        .amount(new Amount(refundPayment.getAmount(), assetName))
        .fee(new Amount(refundPayment.getFee(), assetName))
        .requestedAt(null)
        .refundedAt(null)
        .build();
  }

  static RefundPayment toRefundPayment(
      org.stellar.anchor.sep31.RefundPayment refundPayment, String assetName) {
    return RefundPayment.builder()
        .id(refundPayment.getId())
        .idType(RefundPayment.IdType.STELLAR)
        .amount(new Amount(refundPayment.getAmount(), assetName))
        .fee(new Amount(refundPayment.getFee(), assetName))
        .requestedAt(null)
        .refundedAt(null)
        .build();
  }

  static Refunds toRefunds(Sep24Refunds refunds, String assetName) {
    // build payments
    RefundPayment[] payments =
        refunds.getRefundPayments().stream()
            .map(refundPayment -> toRefundPayment(refundPayment, assetName))
            .toArray(RefundPayment[]::new);

    return Refunds.builder()
        .amountRefunded(new Amount(refunds.getAmountRefunded(), assetName))
        .amountFee(new Amount(refunds.getAmountFee(), assetName))
        .payments(payments)
        .build();
  }

  static Refunds toRefunds(Sep31Refunds refunds, String assetName) {
    // build payments
    RefundPayment[] payments =
        refunds.getRefundPayments().stream()
            .map(refundPayment -> toRefundPayment(refundPayment, assetName))
            .toArray(RefundPayment[]::new);

    return Refunds.builder()
        .amountRefunded(new Amount(refunds.getAmountRefunded(), assetName))
        .amountFee(new Amount(refunds.getAmountFee(), assetName))
        .payments(payments)
        .build();
  }
}
