package org.stellar.anchor.api.sep.sep6;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

/**
 * The request body of the GET /withdraw-exchange endpoint.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0006.md#request-3">GET
 *     /withdraw-exchange</a>
 */
@Builder
@Data
public class StartWithdrawExchangeRequest {
  /** The asset code of the on-chain asset the user wants to withdraw. */
  @NonNull
  @SerializedName("source_asset")
  String sourceAsset;

  /** The SEP-38 identification of the off-chain asset the Anchor will send to the user. */
  @NonNull
  @SerializedName("destination_asset")
  String destinationAsset;

  /**
   * The ID returned from a SEP-38 POST /quote response. If this parameter is provided and the user
   * delivers the deposit funds to the Anchor before the quote expiration, the Anchor should respect
   * the conversion rate agreed in that quote.
   */
  @SerializedName("quote_id")
  String quoteId;

  /** The account to withdraw from. */
  String account;

  /** The amount of the source asset the user would like to withdraw. */
  @NonNull String amount;

  /** Withdraw method used for the transaction. */
  @SerializedName("funding_method")
  String fundingMethod;

  /** The type of withdrawal to make. Deprecated in favor of funding_method */
  @Deprecated String type;

  /** The ISO 3166-1 alpha-3 code of the user's current address. */
  @SerializedName("country_code")
  String countryCode;

  /** The memo the anchor must use when sending refund payments back to the user. */
  @SerializedName("refund_memo")
  String refundMemo;

  /** The type of the refund_memo. */
  @SerializedName("refund_memo_type")
  String refundMemoType;

  /** The request client IP address. */
  String requestClientIpAddress;
}
