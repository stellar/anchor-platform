package org.stellar.anchor.api.sep.sep24;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import lombok.Data;
import org.stellar.anchor.api.shared.FeeDetails;

/** Base class of transaction responses for withdraw and deposit. */
@Data
public class TransactionResponse {
  String id;

  String kind;

  String status;

  @SerializedName("status_eta")
  Integer status_eta;

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

  @SerializedName("started_at")
  Instant startedAt;

  @SerializedName("completed_at")
  Instant completedAt;

  @SerializedName("user_action_required_by")
  Instant userActionRequiredBy;

  @SerializedName("stellar_transaction_id")
  String stellarTransactionId;

  @SerializedName("external_transaction_id")
  String externalTransactionId;

  String message;
  Boolean refunded = false;
  Refunds refunds;
  String from;
  String to;
}
