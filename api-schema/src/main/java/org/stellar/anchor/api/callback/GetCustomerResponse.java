package org.stellar.anchor.api.callback;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerResponse;
import org.stellar.anchor.api.shared.CustomerField;
import org.stellar.anchor.api.shared.ProvidedCustomerField;

/**
 * The response body of the GET /customer endpoint.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-docs/blob/main/openapi/anchor-platform/Callbacks%20API.yml">Callback
 *     API</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class GetCustomerResponse {
  String id;
  String status;
  Map<String, CustomerField> fields;

  @SerializedName("provided_fields")
  Map<String, ProvidedCustomerField> providedFields;

  String message;

  public static Sep12GetCustomerResponse to(GetCustomerResponse response) {
    Gson gson = new Gson();
    return new Gson().fromJson(gson.toJson(response), Sep12GetCustomerResponse.class);
  }
}
