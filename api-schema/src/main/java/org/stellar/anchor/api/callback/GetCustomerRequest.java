package org.stellar.anchor.api.callback;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerRequest;

/**
 * The request body of GET /customer endpoint.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-docs/blob/main/openapi/anchor-platform/Callbacks%20API.yml">Callback
 *     API</a>
 */
@Data
@Builder
public class GetCustomerRequest {
  String id;
  String account;
  String memo;

  @SerializedName("transaction_id")
  String transactionId;

  @SerializedName("memo_type")
  String memoType;

  String type;
  String lang;

  public static GetCustomerRequest from(Sep12GetCustomerRequest request) {
    Gson gson = new Gson();
    return new Gson().fromJson(gson.toJson(request), GetCustomerRequest.class);
  }
}
