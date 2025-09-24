package org.stellar.anchor.api.rpc.method;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.api.rpc.method.features.SupportsUserActionRequiredBy;

@Deprecated(since = "3.0") // ANCHOR-777
@Data
@SuperBuilder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RequestCustomerInfoUpdateRequest extends RpcMethodParamsRequest
    implements SupportsUserActionRequiredBy {

  @Deprecated
  @SerializedName("required_customer_info_message")
  private String requiredCustomerInfoMessage;

  @Deprecated
  @SerializedName("required_customer_info_updates")
  private List<String> requiredCustomerInfoUpdates;

  @SerializedName("user_action_required_by")
  Instant userActionRequiredBy;
}
