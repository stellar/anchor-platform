package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.util.List;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.auth.AuthConfig;
import org.stellar.anchor.auth.AuthHelper;
import org.stellar.anchor.auth.AuthType;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.util.KeyUtil;
import org.stellar.anchor.util.NetUtil;

@Data
public class CallbackApiConfig implements Validator {
  private static long MIN_EXPIRATION = 5000;

  String baseUrl;

  Boolean checkCertificate;

  AuthConfig auth;
  PropertySecretConfig secretConfig;

  public CallbackApiConfig(PropertySecretConfig secretConfig) {
    this.secretConfig = secretConfig;
  }

  public void setAuth(AuthConfig auth) {
    auth.setSecretString(secretConfig.getCallbackAuthSecret());
    this.auth = auth;
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return CallbackApiConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    validateBaseUrl(errors);
    validateAuth(errors);
  }

  void validateBaseUrl(Errors errors) {
    if (isEmpty(baseUrl)) {
      errors.rejectValue(
          "baseUrl",
          "empty-callback-api-base-url",
          "The callback_api.base_url cannot be empty and must be defined");
    }
    if (!NetUtil.isUrlValid(baseUrl)) {
      errors.rejectValue(
          "baseUrl",
          "mal-formatted-callback-api-base-url",
          "The callback_api.base_url is not a valid URL");
    }
  }

  void validateAuth(Errors errors) {
    if (List.of(AuthType.API_KEY, AuthType.JWT).contains(auth.getType())) {
      if (isEmpty(secretConfig.getCallbackAuthSecret())) {
        errors.reject(
            "empty-secret-callback-api-secret",
            "Please set environment variable secret.callback_api.auth_secret or SECRET.CALLBACK_API.AUTH_SECRET");
      }

      if (AuthType.JWT == auth.getType()) {
        KeyUtil.rejectWeakJWTSecret(
            secretConfig.getCallbackAuthSecret(), errors, "secret.callback_api.auth_secret");

        if (Long.parseLong(auth.getJwt().getExpirationMilliseconds()) < MIN_EXPIRATION) {
          errors.rejectValue(
              "auth",
              "min-expiration-milliseconds",
              "expirationMilliseconds cannot be lower than " + MIN_EXPIRATION);
        }
      }
    }
  }

  public AuthHelper buildAuthHelper() {
    String secret = getAuth().getSecretString();
    switch (getAuth().getType()) {
      case JWT:
        return AuthHelper.forJwtToken(
            getAuth().getJwt().getHttpHeader(),
            JwtService.builder().callbackAuthSecret(secret).build(),
            Long.parseLong(getAuth().getJwt().getExpirationMilliseconds()));
      case API_KEY:
        return AuthHelper.forApiKey(getAuth().getApiKey().getHttpHeader(), secret);
      default:
        return AuthHelper.forNone();
    }
  }
}
