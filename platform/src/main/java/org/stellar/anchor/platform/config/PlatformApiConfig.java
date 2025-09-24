package org.stellar.anchor.platform.config;

import static org.stellar.anchor.auth.AuthType.API_KEY;
import static org.stellar.anchor.auth.AuthType.JWT;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.util.List;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.auth.AuthConfig;
import org.stellar.anchor.auth.AuthType;
import org.stellar.anchor.util.KeyUtil;

@Data
public class PlatformApiConfig implements Validator {
  String baseUrl;
  AuthConfig auth;
  PropertySecretConfig secretConfig;

  public PlatformApiConfig(PropertySecretConfig secretConfig) {
    this.secretConfig = secretConfig;
  }

  public void setAuth(AuthConfig auth) {
    auth.setSecretString(secretConfig.getPlatformAuthSecret());
    this.auth = auth;
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return PlatformApiConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    if (List.of(AuthType.API_KEY, AuthType.JWT).contains(auth.getType())) {
      if (isEmpty(secretConfig.getPlatformAuthSecret())) {
        errors.reject(
            "empty-secret-platform-api-secret",
            "Please set environment variable secret.platform_api.auth_secret or SECRET.PLATFORM_API.AUTH_SECRET");
      }

      PlatformApiConfig config = (PlatformApiConfig) target;
      if (List.of(API_KEY, JWT).contains(config.getAuth().getType())) {
        if (AuthType.JWT == auth.getType()) {
          KeyUtil.rejectWeakJWTSecret(
              secretConfig.getPlatformAuthSecret(), errors, "secret.platform_api.auth_secret");
        }
        if (config.getAuth().getSecretString() == null) {
          errors.rejectValue(
              "secret",
              "empty-secret",
              "Please set environment variable [SECRET.PLATFORM_API.AUTH_SECRET] for auth type:"
                  + config.getAuth().getType());
        }
      }
    }
  }
}
