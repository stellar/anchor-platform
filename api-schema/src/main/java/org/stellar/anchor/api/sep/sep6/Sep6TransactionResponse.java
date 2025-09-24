package org.stellar.anchor.api.sep.sep6;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.InstructionField;
import org.stellar.anchor.api.shared.Refunds;

@Data
@Builder
public class Sep6TransactionResponse {
  String id;

  String kind;

  String status;

  @SerializedName("status_eta")
  Long statusEta;

  @SerializedName("more_info_url")
  String moreInfoUrl;

  @SerializedName("amount_in")
  String amountIn;

  @SerializedName("amount_in_asset")
  String amountInAsset;

  @SerializedName("amount_out")
  String amountOut;

  @SerializedName("amount_out_asset")
  String amountOutAsset;

  @SerializedName("amount_fee")
  @Deprecated // ANCHOR-636
  String amountFee;

  @SerializedName("amount_fee_asset")
  @Deprecated // ANCHOR-636
  String amountFeeAsset;

  @SerializedName("fee_details")
  FeeDetails feeDetails;

  @SerializedName("quote_id")
  String quoteId;

  String from;

  String to;

  @SerializedName("deposit_memo")
  String depositMemo;

  @SerializedName("deposit_memo_type")
  String depositMemoType;

  @SerializedName("withdraw_anchor_account")
  String withdrawAnchorAccount;

  @SerializedName("withdraw_memo")
  String withdrawMemo;

  @SerializedName("withdraw_memo_type")
  String withdrawMemoType;

  @SerializedName("started_at")
  String startedAt;

  @SerializedName("updated_at")
  String updatedAt;

  @SerializedName("completed_at")
  String completedAt;

  @SerializedName("user_action_required_by")
  String userActionRequiredBy;

  @SerializedName("stellar_transaction_id")
  String stellarTransactionId;

  @SerializedName("external_transaction_id")
  String externalTransactionId;

  String message;

  Refunds refunds;

  @SerializedName("required_info_message")
  String requiredInfoMessage;

  @SerializedName("required_info_updates")
  List<String> requiredInfoUpdates;

  @Deprecated
  @SerializedName("required_customer_info_message")
  String requiredCustomerInfoMessage;

  @Deprecated
  @SerializedName("required_customer_info_updates")
  List<String> requiredCustomerInfoUpdates;

  Map<String, InstructionField> instructions;
}
