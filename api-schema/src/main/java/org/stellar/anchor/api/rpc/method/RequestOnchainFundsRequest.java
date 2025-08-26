package org.stellar.anchor.api.rpc.method;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.api.rpc.method.features.SupportsUserActionRequiredBy;
import org.stellar.anchor.api.shared.FeeDetails;

@Data
@SuperBuilder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RequestOnchainFundsRequest extends RpcMethodParamsRequest
    implements SupportsUserActionRequiredBy {

  @SerializedName("amount_in")
  private AmountAssetRequest amountIn;

  @SerializedName("amount_out")
  private AmountAssetRequest amountOut;

  @SerializedName("fee_details")
  private FeeDetails feeDetails;

  @SerializedName("amount_expected")
  private AmountRequest amountExpected;

  @SerializedName("destination_account")
  private String destinationAccount;

  // The memo must be a MEMO_ID type.
  private String memo;

  @SerializedName("user_action_required_by")
  Instant userActionRequiredBy;
}
