package org.stellar.anchor.api.rpc.method;

import com.google.gson.annotations.SerializedName;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class NotifyOnchainFundsSentRequest extends RpcMethodParamsRequest {

  @SerializedName("stellar_transaction_id")
  @NotBlank
  private String stellarTransactionId;
}
