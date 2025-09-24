package org.stellar.anchor.api.platform;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.StellarId;

@Data
@SuperBuilder
@NoArgsConstructor
public class GetQuoteResponse {
  String id;

  @JsonProperty("sell_amount")
  @SerializedName("sell_amount")
  String sellAmount;

  @JsonProperty("sell_asset")
  @SerializedName("sell_asset")
  String sellAsset;

  @JsonProperty("buy_amount")
  @SerializedName("buy_amount")
  String buyAmount;

  @JsonProperty("buy_asset")
  @SerializedName("buy_asset")
  String buyAsset;

  @JsonProperty("expires_at")
  @SerializedName("expires_at")
  Instant expiresAt;

  String price;

  @JsonProperty("total_price")
  @SerializedName("total_price")
  String totalPrice;

  StellarId creator;

  @JsonProperty("transaction_id")
  @SerializedName("transaction_id")
  String transactionId;

  @JsonProperty("created_at")
  @SerializedName("created_at")
  Instant createdAt;

  FeeDetails fee;
}
