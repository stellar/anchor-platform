package org.stellar.anchor.api.sep.sep31;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * The response body of the /info endpoint of the SEP-31.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0031.md#fields">Refer
 *     to SEP-31</a>
 */
@Data
public class Sep31InfoResponse {
  Map<String, AssetResponse> receive;

  @Data
  public static class AssetResponse {
    Boolean enabled = true;

    @SerializedName("quotes_supported")
    Boolean quotesSupported;

    @SerializedName("quotes_required")
    Boolean quotesRequired;

    @SerializedName("min_amount")
    Long minAmount;

    @SerializedName("max_amount")
    Long maxAmount;

    @SerializedName("funding_methods")
    List<String> fundingMethods;

    Sep12Response sep12;
  }

  @Data
  public static class Sep12Response {
    Sep12TypesResponse sender;
    Sep12TypesResponse receiver;
  }

  @Data
  public static class Sep12TypesResponse {
    Map<String, Sep12TypeResponse> types;
  }

  @Data
  public static class Sep12TypeResponse {
    String description;
  }
}
