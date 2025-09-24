package org.stellar.anchor.api.sep.sep38;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import lombok.Builder;
import lombok.Data;
import org.stellar.anchor.api.shared.FeeDetails;

/**
 * The response body of the POST /quote endpoint of SEP-38.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0038.md#fields">Refer
 *     to SEP-38</a>
 */
@Data
@Builder
public class Sep38QuoteResponse {
  String id;

  @SerializedName("expires_at")
  Instant expiresAt;

  String price;

  @SerializedName("total_price")
  String totalPrice;

  @SerializedName("sell_asset")
  String sellAsset;

  @SerializedName("sell_amount")
  String sellAmount;

  @SerializedName("buy_asset")
  String buyAsset;

  @SerializedName("buy_amount")
  String buyAmount;

  FeeDetails fee;
}
