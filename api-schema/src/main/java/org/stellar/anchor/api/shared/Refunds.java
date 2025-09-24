package org.stellar.anchor.api.shared;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class Refunds implements SepRefunds {

  @JsonProperty("amount_refunded")
  @SerializedName("amount_refunded")
  Amount amountRefunded;

  @JsonProperty("amount_fee")
  @SerializedName("amount_fee")
  Amount amountFee;

  RefundPayment[] payments;

  public Refunds() {}

  @Override
  public boolean hasRefundPayments() {
    return payments != null;
  }
}
