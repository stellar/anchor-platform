package org.stellar.anchor.client;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

public interface ClientConfig {
  String getName();

  CallbackUrls getCallbackUrls();

  @Data
  @Builder
  class CallbackUrls {
    private String sep6;
    private String sep24;
    private String sep31;
    private String sep12;
  }

  default boolean matchesDomain(String domain) {
    return false;
  }

  /**
   * Returns true if any of the callback URLs are set.
   *
   * @return true if any of the callback URLs are set
   */
  default boolean isCallbackEnabled() {
    return getCallbackUrls() != null
        && (!StringUtils.isEmpty(getCallbackUrls().getSep6())
            || !StringUtils.isEmpty(getCallbackUrls().getSep24())
            || !StringUtils.isEmpty(getCallbackUrls().getSep31())
            || !StringUtils.isEmpty(getCallbackUrls().getSep12()));
  }

  enum ClientType {
    @SerializedName("custodial")
    CUSTODIAL("custodial"),
    @SerializedName("noncustodial")
    NONCUSTODIAL("noncustodial");

    private final String name;

    ClientType(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
