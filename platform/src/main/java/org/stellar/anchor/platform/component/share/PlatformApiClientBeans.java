package org.stellar.anchor.platform.component.share;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.stellar.anchor.apiclient.PlatformApiClient;
import org.stellar.anchor.auth.AuthHelper;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.platform.config.PlatformApiConfig;

@Configuration
public class PlatformApiClientBeans {
  @Bean
  PlatformApiClient platformApiClient(PlatformApiConfig platformApiConfig, AuthHelper authHelper) {
    return new PlatformApiClient(authHelper, platformApiConfig.getBaseUrl());
  }

  @Bean
  AuthHelper authHelper(PlatformApiConfig platformApiConfig) {
    String secret = platformApiConfig.getAuth().getSecretString();
    switch (platformApiConfig.getAuth().getType()) {
      case JWT:
        return AuthHelper.forJwtToken(
            platformApiConfig.getAuth().getJwt().getHttpHeader(),
            JwtService.builder().platformAuthSecret(secret).build(),
            Long.parseLong(platformApiConfig.getAuth().getJwt().getExpirationMilliseconds()));
      case API_KEY:
        return AuthHelper.forApiKey(
            platformApiConfig.getAuth().getApiKey().getHttpHeader(), secret);
      default:
        return AuthHelper.forNone();
    }
  }
}
