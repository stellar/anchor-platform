package org.stellar.anchor.api.rpc.method;

import com.google.gson.annotations.SerializedName;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class NotifyRefundPendingRequest extends RpcMethodParamsRequest {

  private Refund refund;

  @Data
  @Builder
  public static class Refund {

    @NotNull private String id;
    @NotNull private AmountAssetRequest amount;

    @SerializedName("amount_fee")
    @NotNull
    private AmountAssetRequest amountFee;
  }
}
