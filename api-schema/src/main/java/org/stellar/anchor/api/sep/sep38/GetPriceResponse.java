package org.stellar.anchor.api.sep.sep38;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import org.stellar.anchor.api.shared.FeeDetails;

/**
 * The response body of the GET /price endpoint of SEP-38.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0038.md#fields">Refer
 *     to SEP-38</a>
 */
@Data
@Builder
public class GetPriceResponse {
  String price;

  @SerializedName("total_price")
  String totalPrice;

  @SerializedName("sell_amount")
  String sellAmount;

  @SerializedName("buy_amount")
  String buyAmount;

  FeeDetails fee;
}
