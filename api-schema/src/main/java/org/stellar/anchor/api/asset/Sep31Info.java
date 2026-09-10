package org.stellar.anchor.api.asset;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class Sep31Info {
  Boolean enabled = false;

  ReceiveOperation receive;

  @SerializedName("quotes_supported")
  boolean quotesSupported;

  @SerializedName("quotes_required")
  boolean quotesRequired;

  /**
   * Advertised in `GET /info`'s `sep12.sender`/`sep12.receiver` so a sending anchor can discover
   * the `type` value to use for the `sender_id`/`receiver_id` customers it registers via SEP-12,
   * and the same `type` value returned in a `customer_info_needed` error. Null (the default) means
   * this asset advertises no SEP-12 customer type for that role -- see {@link
   * org.stellar.anchor.api.sep.sep31.Sep31InfoResponse.AssetResponse}.
   */
  Sep12Info sep12;

  @Data
  public static class ReceiveOperation {
    @SerializedName("min_amount")
    Long minAmount;

    @SerializedName("max_amount")
    Long maxAmount;

    List<String> methods;
  }

  @Data
  public static class Fields {
    Map<String, AssetInfo.Field> transaction;
  }

  @Data
  public static class Sep12Info {
    Sep12TypeInfo sender;
    Sep12TypeInfo receiver;
  }

  @Data
  public static class Sep12TypeInfo {
    String description;
  }
}
