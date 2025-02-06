package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.config.Sep45Config;
import org.stellar.anchor.util.KeyUtil;

// TODO(philip): Improve configuration validation
@Data
public class PropertySep45Config implements Sep45Config, Validator {
  private Boolean enabled;
  private String webAuthDomain;
  private String webAuthContractId;
  private List<String> homeDomains;
  private Integer jwtTimeout =
      86400; // TODO: this is hardcoded in SEP-10 as well, it should configurable
  private AppConfig appConfig;
  private ClientService clientService;
  private SecretConfig secretConfig;

  public PropertySep45Config(
      AppConfig appConfig, ClientService clientService, SecretConfig secretConfig) {
    this.appConfig = appConfig;
    this.clientService = clientService;
    this.secretConfig = secretConfig;
  }

  @PostConstruct
  public void postConstruct() {
    if (isEmpty(webAuthDomain)) {
      if (homeDomains.size() == 1 && !homeDomains.get(0).contains("*")) {
        webAuthDomain = homeDomains.get(0);
      }
    }
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return Sep45Config.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    PropertySep45Config config = (PropertySep45Config) target;
    if (!config.getEnabled()) {
      return;
    }

    if (isEmpty(secretConfig.getSep45SimulatingSigningSeed())) {
      errors.reject(
          "sep45-simulating-signing-seed-empty",
          "Please set the secret.sep45.simulating_signing_seed or SECRET_SEP45_SIMULATING_SIGNING_SEED environment variable");
    }

    if (isEmpty(secretConfig.getSep45JwtSecretKey())) {
      errors.reject(
          "sep45-jwt-secret-empty",
          "Please set the secret.sep45.jwt_secret or SECRET_SEP45_JWT_SECRET environment variable");
    }

    KeyUtil.rejectWeakJWTSecret(
        secretConfig.getSep45JwtSecretKey(), errors, "secret.sep45.jwt_secret");

    if (homeDomains == null || homeDomains.isEmpty()) {
      errors.reject(
          "sep45-home-domains-empty",
          "Please set the sep45.home_domains or SEP45_HOME_DOMAINS environment variable");
    }
  }
}
