package org.stellar.anchor.api.rpc.method;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.api.rpc.method.features.SupportsUserActionRequiredBy;

@Data
@SuperBuilder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class NotifyOffchainFundsPendingRequest extends RpcMethodParamsRequest
    implements SupportsUserActionRequiredBy {

  @SerializedName("external_transaction_id")
  private String externalTransactionId;

  @SerializedName("user_action_required_by")
  Instant userActionRequiredBy;
}
