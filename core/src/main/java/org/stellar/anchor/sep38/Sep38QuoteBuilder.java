package org.stellar.anchor.sep38;

import java.time.Instant;
import org.stellar.anchor.api.shared.FeeDetails;

public class Sep38QuoteBuilder {
  final Sep38Quote quote;

  public Sep38QuoteBuilder(Sep38QuoteStore factory) {
    quote = factory.newInstance();
  }

  public Sep38QuoteBuilder id(String id) {
    quote.setId(id);
    return this;
  }

  public Sep38QuoteBuilder expiresAt(Instant expiresAt) {
    quote.setExpiresAt(expiresAt);
    return this;
  }

  public Sep38QuoteBuilder price(String price) {
    quote.setPrice(price);
    return this;
  }

  public Sep38QuoteBuilder totalPrice(String totalPrice) {
    quote.setTotalPrice(totalPrice);
    return this;
  }

  public Sep38QuoteBuilder sellAsset(String sellAsset) {
    quote.setSellAsset(sellAsset);
    return this;
  }

  public Sep38QuoteBuilder sellAmount(String sellAmount) {
    quote.setSellAmount(sellAmount);
    return this;
  }

  public Sep38QuoteBuilder sellDeliveryMethod(String sellDeliveryMethod) {
    quote.setSellDeliveryMethod(sellDeliveryMethod);
    return this;
  }

  public Sep38QuoteBuilder buyAsset(String buyAsset) {
    quote.setBuyAsset(buyAsset);
    return this;
  }

  public Sep38QuoteBuilder buyAmount(String buyAmount) {
    quote.setBuyAmount(buyAmount);
    return this;
  }

  public Sep38QuoteBuilder buyDeliveryMethod(String buyDeliveryMethod) {
    quote.setBuyDeliveryMethod(buyDeliveryMethod);
    return this;
  }

  public Sep38QuoteBuilder createdAt(Instant createdAt) {
    quote.setCreatedAt(createdAt);
    return this;
  }

  public Sep38QuoteBuilder creatorAccountId(String creatorAccountId) {
    quote.setCreatorAccountId(creatorAccountId);
    return this;
  }

  public Sep38QuoteBuilder creatorMemo(String creatorMemo) {
    quote.setCreatorMemo(creatorMemo);
    return this;
  }

  public Sep38QuoteBuilder creatorMemoType(String creatorMemoType) {
    quote.setCreatorMemoType(creatorMemoType);
    return this;
  }

  public Sep38QuoteBuilder transactionId(String transactionId) {
    quote.setTransactionId(transactionId);
    return this;
  }

  public Sep38QuoteBuilder fee(FeeDetails fee) {
    quote.setFee(fee);
    return this;
  }

  public Sep38Quote build() {
    return quote;
  }
}
