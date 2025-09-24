package org.stellar.anchor.sep38;

import java.time.Instant;
import org.stellar.anchor.api.shared.FeeDetails;

public interface Sep38Quote {
  String getId();

  void setId(String id);

  Instant getExpiresAt();

  void setExpiresAt(Instant expiresAt);

  String getPrice();

  void setPrice(String price);

  String getTotalPrice();

  void setTotalPrice(String totalPrice);

  String getSellAsset();

  void setSellAsset(String sellAsset);

  String getSellAmount();

  void setSellAmount(String sellAmount);

  String getSellDeliveryMethod();

  void setSellDeliveryMethod(String sellDeliveryMethod);

  String getBuyAsset();

  void setBuyAsset(String buyAsset);

  String getBuyAmount();

  void setBuyAmount(String buyAmount);

  @SuppressWarnings("unused")
  String getBuyDeliveryMethod();

  void setBuyDeliveryMethod(String buyDeliveryMethod);

  Instant getCreatedAt();

  void setCreatedAt(Instant createdAt);

  String getCreatorAccountId();

  void setCreatorAccountId(String creatorAccountId);

  String getCreatorMemo();

  void setCreatorMemo(String creatorMemo);

  String getCreatorMemoType();

  void setCreatorMemoType(String creatorMemoType);

  String getTransactionId();

  void setTransactionId(String transactionId);

  FeeDetails getFee();

  void setFee(FeeDetails fee);
}
