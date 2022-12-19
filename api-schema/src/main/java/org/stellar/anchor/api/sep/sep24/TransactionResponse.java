package org.stellar.anchor.api.sep.sep24;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import lombok.Data;

@Data
public class TransactionResponse {
  String id;

  String kind;

  String status;

  @SerializedName("status_eta")
  Integer status_eta;

  @SerializedName("more_info_url")
  String moreInfoUrl = "http://www.stellar.org";

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

  @SerializedName("started_at")
  Instant startedAt;

  @SerializedName("completed_at")
  Instant completedAt;

  @SerializedName("stellar_transaction_id")
  String stellarTransactionId;

  @SerializedName("external_transaction_id")
  String externalTransactionId;

  String message;

  @Deprecated // Deprecated in favor of refunds
  Boolean refunded = false;
  Refunds refunds;

  String from;

  String to;
}
