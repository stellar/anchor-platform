package org.stellar.anchor.platform.config;

import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.anchor.util.StringHelper.isNotEmpty;

import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.config.Sep45Config;
import org.stellar.anchor.util.NetUtil;
import org.stellar.sdk.Address;
import org.stellar.sdk.scval.Scv;

@Data
public class PropertySep45Config implements Sep45Config, Validator {
  private Boolean enabled;
  private String webAuthDomain;
  private String webAuthContractId;
  private List<String> homeDomains;
  private Integer jwtTimeout;
  private Integer authTimeout;
  private AppConfig appConfig;
  private SecretConfig secretConfig;

  public PropertySep45Config(AppConfig appConfig, SecretConfig secretConfig) {
    this.appConfig = appConfig;
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

    if (isEmpty(appConfig.getRpcUrl())) {
      errors.reject(
          "stellar-network-rpc-url-empty",
          "The stellar_network.rpc_url is not defined. It is required for SEP-45.");
    }

    if (isEmpty(secretConfig.getSep45SimulatingSigningSeed())) {
      errors.reject(
          "sep45-simulating-seed-empty",
          "Please set the secret.sep45.simulating_signing_seed or SECRET_SEP45_SIMULATING_SIGNING_SEED environment variable");
    }

    if (isEmpty(secretConfig.getSep45JwtSecretKey())) {
      errors.reject(
          "sep45-jwt-secret-empty",
          "Please set the secret.sep45.jwt_secret or SECRET_SEP45_JWT_SECRET environment variable");
    }

    if (homeDomains == null || homeDomains.isEmpty()) {
      errors.reject(
          "sep45-home-domains-empty",
          "Please set the sep45.home_domains or SEP45_HOME_DOMAINS environment variable");
    } else {
      for (String domain : homeDomains) {
        if (!NetUtil.isServerPortValid(domain, false)) {
          errors.rejectValue(
              "homeDomain",
              "sep45-home-domain-invalid",
              "The sep45.home_domain does not have valid format.");
        }
      }
    }

    if (isEmpty(webAuthContractId)) {
      errors.reject(
          "sep45-web-auth-contract-id-empty",
          "Please set the sep45.web_auth_contract_id or SEP45_WEB_AUTH_CONTRACT_ID environment variable");
    } else {
      try {
        Address.fromSCAddress(Scv.fromAddress(Scv.toAddress(webAuthContractId)).toSCAddress());
      } catch (RuntimeException ex) {
        errors.reject(
            "sep45-web-auth-contract-id-invalid",
            "The sep45.web_auth_contract_id does not have valid format.");
      }
    }

    if (isNotEmpty(webAuthDomain)) {
      if (!NetUtil.isServerPortValid(webAuthDomain, false)) {
        errors.reject(
            "sep45-home-domain-invalid", "The sep45.home_domain does not have valid format.");
      }
    } else if (homeDomains != null
        && !homeDomains.isEmpty()
        && (homeDomains.size() > 1 || homeDomains.get(0).contains("*"))) {
      errors.rejectValue(
          "sep45-web-auth-domain-empty",
          "The sep45.web_auth_domain is required for multiple home domains.");
    }

    if (jwtTimeout <= 0) {
      errors.rejectValue(
          "sep45-jwt-timeout-invalid", "The sep45.jwt_timeout must be greater than 0");
    }

    if (authTimeout <= 0) {
      errors.rejectValue(
          "sep45-auth-timeout-invalid", "The sep45.auth_timeout must be greater than 0");
    }
  }
}
