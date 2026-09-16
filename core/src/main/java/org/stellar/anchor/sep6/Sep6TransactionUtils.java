package org.stellar.anchor.sep6;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.sep.sep6.Sep6TransactionResponse;
import org.stellar.anchor.api.shared.RefundPayment;
import org.stellar.anchor.api.shared.Refunds;
import org.stellar.anchor.util.StringHelper;

public class Sep6TransactionUtils {

  /**
   * SEP-6 stores `required_info_updates` as a flat list of field names (there is no per-field
   * metadata store for SEP-6, unlike SEP-31's `Sep31Info.Fields`), but the SEP-6 spec requires the
   * wire response in the same per-field object format as `/info` (name -> {description, choices,
   * optional}). Synthesize that shape here, at the response boundary only -- a humanized version of
   * the field name is the best available description, since no richer metadata exists upstream.
   */
  private static Map<String, AssetInfo.Field> toRequiredInfoUpdatesFields(
      List<String> requiredInfoUpdates) {
    if (requiredInfoUpdates == null || requiredInfoUpdates.isEmpty()) {
      return null;
    }
    Map<String, AssetInfo.Field> fields = new LinkedHashMap<>();
    for (String fieldName : requiredInfoUpdates) {
      fields.put(
          fieldName,
          AssetInfo.Field.builder().description(StringHelper.humanizeSnakeCase(fieldName)).build());
    }
    return fields;
  }

  /**
   * Converts a SEP-6 database transaction object to a SEP-6 API transaction object.
   *
   * @param txn the SEP-6 database transaction object
   * @param moreInfoUrlConstructor the more_info_url constructor created from SEP-6 config
   * @param lang the language code from the SEP-6 GET /transaction(s) request.
   * @return the SEP-6 API transaction object
   */
  public static Sep6TransactionResponse fromTxn(
      Sep6Transaction txn, MoreInfoUrlConstructor moreInfoUrlConstructor, String lang) {
    Refunds refunds = null;
    if (txn.getRefunds() != null && txn.getRefunds().getPayments() != null) {
      List<RefundPayment> payments = new ArrayList<>();
      for (RefundPayment payment : txn.getRefunds().getPayments()) {
        payments.add(
            RefundPayment.builder()
                .id(payment.getId())
                .idType(payment.getIdType())
                .amount(payment.getAmount())
                .fee(payment.getFee())
                .build());
      }
      refunds =
          Refunds.builder()
              .amountRefunded(txn.getRefunds().getAmountRefunded())
              .amountFee(txn.getRefunds().getAmountFee())
              .payments(payments.toArray(new RefundPayment[0]))
              .build();
    }
    Sep6TransactionResponse.Sep6TransactionResponseBuilder builder =
        Sep6TransactionResponse.builder()
            .id(txn.getId())
            .kind(txn.getKind())
            .status(txn.getStatus())
            .statusEta(txn.getStatusEta())
            .moreInfoUrl(moreInfoUrlConstructor.construct(txn, lang))
            .amountIn(txn.getAmountIn())
            .amountInAsset(txn.getAmountInAsset())
            .amountOut(txn.getAmountOut())
            .amountOutAsset(txn.getAmountOutAsset())
            .feeDetails(txn.getFeeDetails())
            .quoteId(txn.getQuoteId())
            .startedAt(txn.getStartedAt().toString())
            .updatedAt((txn.getUpdatedAt() != null) ? txn.getUpdatedAt().toString() : null)
            .completedAt(txn.getCompletedAt() != null ? txn.getCompletedAt().toString() : null)
            .userActionRequiredBy(
                txn.getUserActionRequiredBy() != null
                    ? txn.getUserActionRequiredBy().toString()
                    : null)
            .stellarTransactionId(txn.getStellarTransactionId())
            .externalTransactionId(txn.getExternalTransactionId())
            .from(txn.getFromAccount())
            .to(txn.getToAccount())
            .message(txn.getMessage())
            .refunds(refunds)
            .requiredInfoMessage(txn.getRequiredInfoMessage())
            .requiredInfoUpdates(toRequiredInfoUpdatesFields(txn.getRequiredInfoUpdates()))
            .instructions(txn.getInstructions());

    if (Sep6Transaction.Kind.valueOf(txn.getKind().toUpperCase().replace("-", "_")).isDeposit()) {
      return builder.depositMemo(txn.getMemo()).depositMemoType(txn.getMemoType()).build();
    } else {
      return builder
          .withdrawAnchorAccount(txn.getWithdrawAnchorAccount())
          .withdrawMemo(txn.getMemo())
          .withdrawMemoType(txn.getMemoType())
          .build();
    }
  }
}
