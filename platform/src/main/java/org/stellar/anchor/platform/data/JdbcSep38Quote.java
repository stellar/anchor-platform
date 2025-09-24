package org.stellar.anchor.platform.data;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import javax.persistence.*;
import lombok.Data;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.sep38.Sep38Quote;

@Data
@Entity
@Table(name = "exchange_quote")
public class JdbcSep38Quote implements Sep38Quote {
  @Id String id;

  @SerializedName("expires_at")
  @Column(name = "expires_at")
  Instant expiresAt;

  String price;

  @SerializedName("total_price")
  @Column(name = "total_price")
  String totalPrice;

  @SerializedName("sell_asset")
  @Column(name = "sell_asset")
  String sellAsset;

  @SerializedName("sell_amount")
  @Column(name = "sell_amount")
  String sellAmount;

  @SerializedName("sell_delivery_method")
  @Column(name = "sell_delivery_method")
  String sellDeliveryMethod;

  @SerializedName("buy_asset")
  @Column(name = "buy_asset")
  String buyAsset;

  @SerializedName("buy_amount")
  @Column(name = "buy_amount")
  String buyAmount;

  @SerializedName("buy_delivery_method")
  @Column(name = "buy_delivery_method")
  String buyDeliveryMethod;

  @SerializedName("created_at")
  @Column(name = "created_at")
  Instant createdAt;

  @SerializedName("creator_account_id")
  @Column(name = "creator_account_id")
  String creatorAccountId;

  @SerializedName("creator_memo")
  @Column(name = "creator_memo")
  String creatorMemo;

  @SerializedName("creator_memo_type")
  @Column(name = "creator_memo_type")
  String creatorMemoType;

  @SerializedName("transaction_id")
  @Column(name = "transaction_id")
  String transactionId;

  @Convert(converter = RateFeeConverter.class)
  @Column(length = 1023)
  FeeDetails fee;
}
