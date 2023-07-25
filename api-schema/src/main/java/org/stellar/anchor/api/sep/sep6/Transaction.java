package org.stellar.anchor.api.sep.sep6;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Transaction {
  String id;

  String kind;

  String status;

  @SerializedName("status_eta")
  String statusEta;

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
  String amountFee;

  @SerializedName("amount_fee_asset")
  String amountFeeAsset;

  @SerializedName("quote_id")
  String quoteId;

  String from;

  String to;

  String depositMemo;

  String depositMemoType;

  String withdrawMemoAccount;

  String withdrawMemo;

  String withdrawMemoType;

  @SerializedName("started_at")
  String startedAt;

  @SerializedName("updated_at")
  String updatedAt;

  @SerializedName("completed_at")
  String completedAt;

  @SerializedName("stellar_transaction_id")
  String stellarTransactionId;

  @SerializedName("external_transaction_id")
  String externalTransactionId;

  String message;

  Boolean refunded;

  Refunds refunds;

  @SerializedName("required_info_message")
  String requiredInfoMessage;

  // TODO: type tbd
  @SerializedName("required_info_updates")
  String requiredInfoUpdates;
}
