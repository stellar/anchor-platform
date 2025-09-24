package org.stellar.anchor.auth;

import lombok.*;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class AuthConfig {
  AuthType type;
  String secretString;
  JwtConfig jwt;
  ApiKeyConfig apiKey;

  @Setter
  @Getter
  @AllArgsConstructor
  @NoArgsConstructor
  public static class JwtConfig {
    String expirationMilliseconds;
    String httpHeader;
  }

  @Setter
  @Getter
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ApiKeyConfig {
    String httpHeader;
  }
}
