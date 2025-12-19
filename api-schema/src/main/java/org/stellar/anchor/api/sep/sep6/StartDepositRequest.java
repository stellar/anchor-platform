package org.stellar.anchor.api.sep.sep6;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

/**
 * The request body of the GET /deposit endpoint.
 *
 * @see <a href="">GET /deposit</a>
 */
@Builder
@Data
public class StartDepositRequest {
  /** The asset code of the asset to deposit. */
  @NonNull
  @SerializedName("asset_code")
  String assetCode;

  /** The Stellar account ID of the user to deposit to. */
  @NonNull String account;

  /** The memo type to use for the deposit. */
  @SerializedName("memo_type")
  String memoType;

  /** The memo to use for the deposit. */
  String memo;

  /** Email address of depositor. Currently, ignored. */
  @SerializedName("email_address")
  String emailAddress;

  /** Deposit method used for the transaction. */
  @SerializedName("funding_method")
  String fundingMethod;

  /** Type of deposit. Deprecated in favor of funding_method */
  @Deprecated String type;

  /** Name of wallet to deposit to. Currently, ignored. */
  @SerializedName("wallet_name")
  String walletName;

  /**
   * Anchor should link to this when notifying the user that the transaction has completed.
   * Currently, ignored
   */
  @SerializedName("wallet_url")
  String walletUrl;

  /**
   * Defaults to en if not specified or if the specified language is not supported. Currently,
   * ignored.
   */
  String lang;

  /** The amount to deposit. */
  String amount;

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
