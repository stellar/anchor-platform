package org.stellar.anchor.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RpcAuthConfig {
  RpcAuthType type;
  XApiKeyConfig xApiKey;
  UrlConfig url;

  public enum RpcAuthType {
    NONE,
    X_API_KEY,
    URL,
  }

  @Getter
  @Setter
  public static class XApiKeyConfig {
    String httpHeader;
  }

  @Getter
  @Setter
  public static class UrlConfig {
    UrlType type;
    String queryParamName;
    String value;

    public enum UrlType {
      PATH_APPEND,
      QUERY_PARAM
    }
  }
}
