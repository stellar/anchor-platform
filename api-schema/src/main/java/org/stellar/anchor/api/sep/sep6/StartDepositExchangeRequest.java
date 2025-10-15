package org.stellar.anchor.api.sep.sep6;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

/**
 * The request body of the GET /deposit-exchange endpoint.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0006.md#request-2">GET
 *     /deposit-exchange</a>
 */
@Builder
@Data
public class StartDepositExchangeRequest {
  /**
   * The asset code of the on-chain asset the user wants to get from the Anchor after doing an
   * off-chain deposit.
   */
  @NonNull
  @SerializedName("destination_asset")
  String destinationAsset;

  /** The SEP-38 identification of the off-chain asset the Anchor will receive from the user. */
  @NonNull
  @SerializedName("source_asset")
  String sourceAsset;

  /**
   * The ID returned from a SEP-38 POST /quote response. If this parameter is provided and the user
   * delivers the deposit funds to the Anchor before the quote expiration, the Anchor should respect
   * the conversion rate agreed in that quote.
   */
  @SerializedName("quote_id")
  String quoteId;

  /** The amount of the source asset the user would like to deposit to the Anchor's off-chain. */
  @NonNull String amount;

  /** The Stellar account ID of the user to deposit to */
  @NonNull String account;

  /** The memo type to use for the deposit. */
  @SerializedName("memo_type")
  String memoType;

  /** The memo to use for the deposit. */
  String memo;

  /** Deposit method used for the transaction. */
  @SerializedName("funding_method")
  String fundingMethod;

  /** Type of deposit. Deprecated in favor of funding_method */
  @Deprecated String type;

  /**
   * Defaults to en if not specified or if the specified language is not supported. Currently,
   * ignored.
   */
  String lang;

  /** The ISO 3166-1 alpha-3 code of the user's current address. */
  @SerializedName("country_code")
  String countryCode;

  /**
   * Whether the client supports receiving deposit transactions as a claimable balance. Currently,
   * unsupported.
   */
  @SerializedName("claimable_balances_supported")
  Boolean claimableBalancesSupported;

  /** The request client IP address. */
  String requestClientIpAddress;
}
