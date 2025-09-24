package org.stellar.anchor.api.callback;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.stellar.anchor.api.shared.FeeDetails;
import reactor.util.annotation.Nullable;

/**
 * The response body of the GET /rate endpoint.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-docs/blob/main/openapi/anchor-platform/Callbacks%20API.yml">Callback
 *     API</a>
 */
@Data
@NoArgsConstructor
public class GetRateResponse {
  public GetRateResponse(Rate rate) {
    this.rate = rate;
  }

  Rate rate;

  /**
   * Builds the response expected for the INDICATIVE_PRICE type.
   *
   * @param price the price between sell asset and buy asset, without including fees, where
   *     `sell_amount - fee = price * buy_amount` or `sell_amount = price * (buy_amount + fee)` must
   *     be true.
   * @param sellAmount the amount of sell_asset the anchor would expect to receive.
   * @param buyAmount the amount of buy_asset the anchor would trade for the sell_amount of
   *     sell_asset.
   * @param fee an object describing the fee used to calculate the conversion price.
   * @return a GET /rate response with price, total_price, sell_amount, buy_amount and fee.
   */
  public static GetRateResponse indicativePrice(
      String price, String sellAmount, String buyAmount, FeeDetails fee) {
    Rate rate =
        Rate.builder().price(price).sellAmount(sellAmount).buyAmount(buyAmount).fee(fee).build();
    return new GetRateResponse(rate);
  }

  @Data
  @Builder
  public static class Rate {
    @Nullable String id;

    String price;

    @SerializedName("sell_amount")
    String sellAmount;

    @SerializedName("buy_amount")
    String buyAmount;

    @SerializedName("expires_at")
    @Nullable
    Instant expiresAt;

    @Nullable FeeDetails fee;
  }
}
