package org.stellar.anchor.api.rpc.method;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class NotifyCustomerInfoUpdatedRequest extends RpcMethodParamsRequest {
  @SerializedName("customer_id")
  String customerId;

  @SerializedName("customer_type")
  String customerType;
}
