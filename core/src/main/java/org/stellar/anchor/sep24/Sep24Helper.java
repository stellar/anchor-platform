package org.stellar.anchor.sep24;

import static org.stellar.anchor.sep24.Sep24Transaction.Kind.WITHDRAWAL;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.MathHelper.decimal;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.api.sep.sep24.*;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.util.GsonUtils;

public class Sep24Helper {
  static Gson gson = GsonUtils.getInstance();

  static void setSharedTransactionResponseFields(TransactionResponse txnR, Sep24Transaction txn) {
    txnR.setId(txn.getTransactionId());
    if (txn.getFromAccount() != null) txnR.setFrom(txn.getFromAccount());
    if (txn.getToAccount() != null) txnR.setTo(txn.getToAccount());
    if (txn.getStartedAt() != null) txnR.setStartedAt(txn.getStartedAt());
    if (txn.getCompletedAt() != null) txnR.setCompletedAt(txn.getCompletedAt());
    if (txn.getQuoteId() != null) txnR.setQuoteId(txn.getQuoteId());
    if (txn.getUserActionRequiredBy() != null)
      txnR.setUserActionRequiredBy(txn.getUserActionRequiredBy());
  }

  static TransactionResponse updateRefundInfo(
      TransactionResponse response, Sep24Transaction txn, AssetInfo assetInfo) {
    debugF("Calculating refund information");

    if (txn.getRefunds() == null) return response;

    List<Sep24RefundPayment> refundPayments = txn.getRefunds().getRefundPayments();
    response.setRefunded(false);
    BigDecimal totalAmount = BigDecimal.ZERO;
    BigDecimal totalFee = BigDecimal.ZERO;

    if (refundPayments != null && refundPayments.size() > 0) {
      debugF("{} refund payments found", refundPayments.size());
      List<RefundPayment> rps = new ArrayList<>(refundPayments.size());
      for (Sep24RefundPayment refundPayment : refundPayments) {
        if (refundPayment.getAmount() != null)
          totalAmount = totalAmount.add(decimal(refundPayment.getAmount(), assetInfo));
        if (refundPayment.getFee() != null)
          totalFee = totalFee.add(decimal(refundPayment.getFee(), assetInfo));
        RefundPayment rp = new RefundPayment();
        BeanUtils.copyProperties(refundPayment, rp);
        rps.add(rp);
      }
      Refunds refunds =
          Refunds.builder()
              .amountRefunded(totalAmount.toString())
              .amountFee(totalFee.toString())
              .payments(rps)
              .build();
      response.setRefunds(refunds);

      if (totalAmount.equals(decimal(response.getAmountIn(), assetInfo))) {
        response.setRefunded(true);
      }
    }

    return response;
  }

  /**
   * Creates a SEP-24 transaction response from a SEP-24 transaction.
   *
   * @param assetService The asset service.
   * @param moreInfoUrlConstructor The more info URL constructor.
   * @param txn The SEP-24 transaction.
   * @param lang The language code from the SEP-24 GET /transaction(s) request.
   * @return The SEP-24 transaction response.
   * @throws SepException If the transaction kind is not supported.
   */
  public static TransactionResponse fromTxn(
      AssetService assetService,
      MoreInfoUrlConstructor moreInfoUrlConstructor,
      Sep24Transaction txn,
      String lang)
      throws SepException {
    debugF(
        "Converting Sep24Transaction to Transaction Response. kind={}, transactionId={}",
        txn.getKind(),
        txn.getTransactionId());
    TransactionResponse response;
    if (txn.getKind().equals(Sep24Transaction.Kind.DEPOSIT.toString())) {
      response = fromDepositTxn(txn, moreInfoUrlConstructor, lang);
    } else if (txn.getKind().equals(WITHDRAWAL.toString())) {
      response = fromWithdrawTxn(txn, moreInfoUrlConstructor, lang);
    } else {
      throw new SepException(String.format("unsupported txn kind:%s", txn.getKind()));
    }

    // Calculate refund information.
    AssetInfo assetInfo =
        assetService.getAsset(txn.getRequestAssetCode(), txn.getRequestAssetIssuer());
    return Sep24Helper.updateRefundInfo(response, txn, assetInfo);
  }

  public static TransactionResponse fromDepositTxn(
      Sep24Transaction txn, MoreInfoUrlConstructor moreInfoUrlConstructor, String lang) {

    DepositTransactionResponse txnR =
        gson.fromJson(gson.toJson(txn), DepositTransactionResponse.class);

    setSharedTransactionResponseFields(txnR, txn);

    txnR.setDepositMemo(txn.getMemo());
    txnR.setDepositMemoType(txn.getMemoType());

    txnR.setMoreInfoUrl(moreInfoUrlConstructor.construct(txn, lang));

    return txnR;
  }

  public static WithdrawTransactionResponse fromWithdrawTxn(
      Sep24Transaction txn, MoreInfoUrlConstructor moreInfoUrlConstructor, String lang) {

    WithdrawTransactionResponse txnR =
        gson.fromJson(gson.toJson(txn), WithdrawTransactionResponse.class);

    setSharedTransactionResponseFields(txnR, txn);

    txnR.setWithdrawMemo(txn.getMemo());
    txnR.setWithdrawMemoType(txn.getMemoType());
    txnR.setWithdrawAnchorAccount(txn.getWithdrawAnchorAccount());

    txnR.setMoreInfoUrl(moreInfoUrlConstructor.construct(txn, lang));

    return txnR;
  }
}
