package org.stellar.anchor.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RpcAuthConfig {
  RpcAuthType type;
  HeaderConfig headerConfig;

  public enum RpcAuthType {
    NONE,
    HEADER
  }

  @Getter
  @Setter
  @AllArgsConstructor
  public static class HeaderConfig {
    String header;
    String value;
  }
}
