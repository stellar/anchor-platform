package org.stellar.anchor.sep38;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import lombok.Data;
import org.stellar.anchor.api.shared.FeeDetails;

@Data
public class PojoSep38Quote implements Sep38Quote {
  @SerializedName("sell_asset")
  String sellAsset;

  @SerializedName("sell_amount")
  String sellAmount;

  @SerializedName("buy_asset")
  String buyAsset;

  @SerializedName("buy_amount")
  String buyAmount;

  String id;
  Instant expiresAt;
  String price;
  String totalPrice;
  String sellDeliveryMethod;
  String buyDeliveryMethod;
  Instant createdAt;
  String creatorAccountId;
  String creatorMemo;
  String creatorMemoType;
  String transactionId;
  FeeDetails fee;
}
