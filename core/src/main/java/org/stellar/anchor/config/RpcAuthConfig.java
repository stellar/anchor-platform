package org.stellar.anchor.config;

import lombok.Data;

public class RpcAuthConfig {
  RpcAuthType type;
  XApiKeyConfig xApiKey;
  UrlConfig url;

  enum RpcAuthType {
    NONE,
    X_API_KEY,
    URL,
  }

  @Data
  static class XApiKeyConfig {
    String httpHeader;
  }

  @Data
  static class UrlConfig {
    UrlType type;
    String queryParamName;

    enum UrlType {
      PATH_APPEND,
      QUERY_PARAM
    }
  }
}
