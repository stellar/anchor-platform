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
import org.stellar.anchor.config.CustodySecretConfig;
import org.stellar.anchor.util.KeyUtil;
import org.stellar.anchor.util.NetUtil;

@Data
public class CustodyApiConfig implements Validator {

  private String baseUrl;
  private HttpClientConfig httpClient;
  private AuthConfig auth;
  private CustodySecretConfig secretConfig;

  public CustodyApiConfig(CustodySecretConfig secretConfig) {
    this.secretConfig = secretConfig;
  }

  public void setAuth(AuthConfig auth) {
    auth.setSecretString(secretConfig.getCustodyAuthSecret());
    this.auth = auth;
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return CustodyApiConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    validateBaseUrl(errors);
    validateApiSecret(errors);
    httpClient.validate("custody-server", errors);
  }

  private void validateBaseUrl(Errors errors) {
    if (isEmpty(baseUrl)) {
      errors.rejectValue(
          "baseUrl",
          "custody-server-base-url-empty",
          "The custody_server.base_url cannot be empty and must be defined");
    }
    if (!NetUtil.isUrlValid(baseUrl)) {
      errors.rejectValue(
          "baseUrl",
          "custody-server-base-url-invalid",
          "The custody_server.base_url is not a valid URL");
    }
  }

  private void validateApiSecret(Errors errors) {
    if (List.of(AuthType.API_KEY, AuthType.JWT).contains(auth.getType())) {
      if (isEmpty(secretConfig.getCustodyAuthSecret())) {
        errors.reject(
            "empty-secret-custody-server-secret",
            "Please set environment variable secret.custody_server.auth_secret or SECRET.CUSTODY_SERVER.AUTH_SECRET");
      }

      if (List.of(API_KEY, JWT).contains(auth.getType())) {
        if (AuthType.JWT == auth.getType()) {
          KeyUtil.rejectWeakJWTSecret(
              secretConfig.getCustodyAuthSecret(), errors, "secret.custody_server.auth_secret");
        }

        if (auth.getSecretString() == null) {
          errors.rejectValue(
              "secret",
              "empty-secret",
              "Please set environment variable [SECRET.CUSTODY_SERVER.AUTH_SECRET] for auth type:"
                  + auth.getType());
        }
      }
    }
  }
}
