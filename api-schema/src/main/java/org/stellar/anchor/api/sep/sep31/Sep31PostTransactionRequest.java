package org.stellar.anchor.api.sep.sep31;

import com.google.gson.annotations.SerializedName;
import java.util.HashMap;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * The request sent to the POST /transactions endpoint of SEP-31.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0031.md#fields">Refer
 *     to SEP-31</a>
 */
@Data
public class Sep31PostTransactionRequest {
  String amount;

  @SerializedName("asset_code")
  String assetCode;

  @SerializedName("asset_issuer")
  String assetIssuer;

  @SerializedName("destination_asset")
  String destinationAsset;

  @SerializedName("quote_id")
  String quoteId;

  @SerializedName("sender_id")
  String senderId;

  @SerializedName("receiver_id")
  String receiverId;

  @Deprecated Sep31TxnFields fields;

  String lang;

  @SerializedName("funding_method")
  String fundingMethod;

  /** The request client IP address. */
  String requestClientIpAddress;

  @Data
  @AllArgsConstructor
  public static class Sep31TxnFields {
    HashMap<String, String> transaction;
  }
}
