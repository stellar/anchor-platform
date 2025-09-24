package org.stellar.anchor.api.callback;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The request body of GET /rate endpoint.
 *
 * @see <a
 *     href="https://github.com/stellar/stellar-docs/blob/main/openapi/anchor-platform/Callbacks%20API.yml">Callback
 *     API</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GetRateRequest {
  Type type;

  @SerializedName("sell_asset")
  String sellAsset;

  @SerializedName("sell_amount")
  String sellAmount;

  @SerializedName("sell_delivery_method")
  String sellDeliveryMethod;

  @SerializedName("buy_asset")
  String buyAsset;

  @SerializedName("buy_amount")
  String buyAmount;

  @SerializedName("buy_delivery_method")
  String buyDeliveryMethod;

  @SerializedName("country_code")
  String countryCode;

  @SerializedName("expire_after")
  String expireAfter;

  @SerializedName("client_id")
  String clientId;

  public enum Type {
    @SerializedName("indicative")
    INDICATIVE("indicative"),

    @SerializedName("firm")
    FIRM("firm");

    private final String name;

    Type(String name) {
      this.name = name;
    }

    public static Type from(String name) {
      for (Type type : values()) {
        if (type.name.equals(name)) {
          return type;
        }
      }
      throw new RuntimeException("Invalid type: " + name);
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
