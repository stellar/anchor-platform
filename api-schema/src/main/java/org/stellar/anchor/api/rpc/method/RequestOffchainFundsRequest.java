package org.stellar.anchor.api.rpc.method;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.api.rpc.method.features.SupportsUserActionRequiredBy;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.InstructionField;

@Data
@SuperBuilder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RequestOffchainFundsRequest extends RpcMethodParamsRequest
    implements SupportsUserActionRequiredBy {

  @SerializedName("amount_in")
  private AmountAssetRequest amountIn;

  @SerializedName("amount_out")
  private AmountAssetRequest amountOut;

  @SerializedName("amount_fee")
  @Deprecated // ANCHOR-636
  private AmountAssetRequest amountFee;

  @SerializedName("fee_details")
  private FeeDetails feeDetails;

  @SerializedName("amount_expected")
  private AmountRequest amountExpected;

  @SerializedName("instructions")
  Map<String, InstructionField> instructions;

  @SerializedName("user_action_required_by")
  Instant userActionRequiredBy;
}
