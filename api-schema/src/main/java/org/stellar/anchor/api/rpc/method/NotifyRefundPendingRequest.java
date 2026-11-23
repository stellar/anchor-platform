package org.stellar.anchor.api.rpc.method;

import com.google.gson.annotations.SerializedName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

  @Valid private Refund refund;

  @Data
  @Builder
  public static class Refund {

    @NotBlank private String id;
    @NotNull @Valid private AmountAssetRequest amount;

    @SerializedName("amount_fee")
    @NotNull
    @Valid
    private AmountAssetRequest amountFee;
  }
}
