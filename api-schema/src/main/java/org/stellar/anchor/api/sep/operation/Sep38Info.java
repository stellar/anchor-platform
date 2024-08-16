package org.stellar.anchor.api.sep.operation;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import lombok.Data;

@Data
public class Sep38Info {
  Boolean enabled = false;

  @SerializedName("exchangeable_assets")
  List<String> exchangeableAssets;

  @SerializedName("country_codes")
  List<String> countryCodes;

  Integer decimals;

  @SerializedName("sell_delivery_methods")
  List<DeliveryMethod> sellDeliveryMethods;

  @SerializedName("buy_delivery_methods")
  List<DeliveryMethod> buyDeliveryMethods;

  @Data
  public static class DeliveryMethod {
    String name;

    String description;

    public DeliveryMethod(String name, String description) {
      this.name = name;
      this.description = description;
    }
  }
}
