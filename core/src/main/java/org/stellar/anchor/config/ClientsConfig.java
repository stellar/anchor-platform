package org.stellar.anchor.config;

import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

public interface ClientsConfig {
  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  @Builder
  class ClientConfig {
    String name;
    ClientType type;
    @Deprecated String signingKey; // ANCHOR-696
    Set<String> signingKeys;
    @Deprecated String domain; // ANCHOR-696
    Set<String> domains;
    @Deprecated String callbackUrl; // ANCHOR-755
    String callbackUrlSep6;
    String callbackUrlSep24;
    String callbackUrlSep31;
    String callbackUrlSep12;
    boolean allowAnyDestination = false;
    Set<String> destinationAccounts;

    /**
     * Returns true if any of the callback URLs are set.
     *
     * @return true if any of the callback URLs are set
     */
    public boolean isCallbackEnabled() {
      return !(StringUtils.isEmpty(callbackUrl)
          && StringUtils.isEmpty(callbackUrlSep6)
          && StringUtils.isEmpty(callbackUrlSep24)
          && StringUtils.isEmpty(callbackUrlSep31)
          && StringUtils.isEmpty(callbackUrlSep12));
    }
  }

  enum ClientType {
    CUSTODIAL,
    NONCUSTODIAL
  }

  ClientConfig getClientConfigBySigningKey(String signingKey);

  ClientConfig getClientConfigByDomain(String domain);
}
