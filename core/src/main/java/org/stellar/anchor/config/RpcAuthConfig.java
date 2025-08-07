package org.stellar.anchor.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RpcAuthConfig {
  RpcAuthType type;
  BearerTokenConfig bearerToken;
  UrlConfig url;

  public enum RpcAuthType {
    NONE,
    URL,
    BEARER_TOKEN
  }

  @Getter
  @Setter
  public static class BearerTokenConfig {
    String header;
    String prefix;
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
