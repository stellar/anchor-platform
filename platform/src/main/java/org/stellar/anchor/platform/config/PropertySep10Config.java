package org.stellar.anchor.platform.config;

import static java.lang.String.format;
import static org.stellar.anchor.config.ClientsConfig.ClientType.CUSTODIAL;
import static org.stellar.anchor.config.ClientsConfig.ClientType.NONCUSTODIAL;
import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.anchor.util.StringHelper.isNotEmpty;

import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.AppConfig;
import org.stellar.anchor.config.ClientsConfig.ClientConfig;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.config.Sep10Config;
import org.stellar.anchor.util.KeyUtil;
import org.stellar.anchor.util.NetUtil;
import org.stellar.anchor.util.StringHelper;
import org.stellar.sdk.*;

@Data
public class PropertySep10Config implements Sep10Config, Validator {
  private Boolean enabled;
  private String webAuthDomain;
  private String homeDomain;
  private List<String> homeDomains;
  private boolean clientAttributionRequired = false;
  private List<String> clientAllowList = null;
  private Integer authTimeout = 900;
  private Integer jwtTimeout = 86400;
  private List<String> knownCustodialAccountList;
  private AppConfig appConfig;
  private final PropertyClientsConfig clientsConfig;
  private SecretConfig secretConfig;
  private boolean requireAuthHeader = false;

  public PropertySep10Config(
      AppConfig appConfig, PropertyClientsConfig clientsConfig, SecretConfig secretConfig) {
    this.appConfig = appConfig;
    this.clientsConfig = clientsConfig;
    this.secretConfig = secretConfig;
    this.knownCustodialAccountList =
        clientsConfig.getClients().stream()
            .filter(cfg -> cfg.getType() == CUSTODIAL && !cfg.getSigningKeys().isEmpty())
            .flatMap(cfg -> cfg.getSigningKeys().stream())
            .collect(Collectors.toList());
  }

  @PostConstruct
  public void postConstruct() {
    // Moving home_domain to home_domains. home_domain will be deprecated in 3.0
    if (homeDomains == null || homeDomains.isEmpty()) {
      homeDomains = List.of(homeDomain);
      homeDomain = null;
    }
    // If webAuthDomain is not specified and there is 1 and only 1 domain in the home_domains
    if (isEmpty(webAuthDomain) && homeDomains.size() == 1) {
      webAuthDomain = homeDomains.get(0);
    }
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return Sep10Config.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    PropertySep10Config config = (PropertySep10Config) target;

    if (config.getEnabled()) {
      validateConfig(errors);
      validateClientAttribution(errors);
      validateCustodialAccounts(errors);
    }
  }

  void validateConfig(Errors errors) {
    if (isEmpty(secretConfig.getSep10SigningSeed())) {
      errors.reject(
          "sep10-signing-seed-empty", "Please set environment variable SECRET_SEP10_SIGNING_SEED");
    }

    if (isNotEmpty(secretConfig.getSep10SigningSeed())) {
      try {
        KeyPair.fromSecretSeed(secretConfig.getSep10SigningSeed());
      } catch (Throwable ex) {
        errors.reject(
            "sep10-signing-seed-invalid",
            "Please set the secret.sep10.signing_seed or SECRET_SEP10_SIGNING_SEED environment variable");
      }
    }

    if (isEmpty(secretConfig.getSep10JwtSecretKey())) {
      errors.reject(
          "sep10-jwt-secret-empty",
          "Please set the secret.sep10.jwt_secret or SECRET_SEP10_JWT_SECRET environment variable");
    }

    KeyUtil.rejectWeakJWTSecret(
        secretConfig.getSep10JwtSecretKey(), errors, "secret.sep10.jwt_secret");

    if (isEmpty(homeDomain) && (homeDomains == null || homeDomains.isEmpty())) {
      // Default to localhost:8080 if neither is defined.
      homeDomains = List.of("localhost:8080");
    } else if (!isEmpty(homeDomain) && (homeDomains != null && !homeDomains.isEmpty())) {
      // Reject if both are defined.
      errors.rejectValue(
          "homeDomain",
          "home-domain-coexist",
          "home_domain and home_domains cannot coexist. Please choose one to use.");
    } else {
      if (!isEmpty(homeDomain)) {
        validateDomain(errors, homeDomain);
      } else {
        for (String domain : homeDomains) {
          validateDomain(errors, domain);
        }
      }
    }

    if (isNotEmpty(webAuthDomain)) {
      try {
        new ManageDataOperation.Builder(webAuthDomain, new byte[64]).build();
      } catch (IllegalArgumentException iaex) {
        errors.rejectValue(
            "webAuthDomain",
            "sep10-web-auth-domain-too-long",
            format(
                "The sep10.web_auth_home_domain (%s) is longer than the maximum length (59) of a domain. Error=%s",
                webAuthDomain, iaex));
      }

      if (!NetUtil.isServerPortValid(webAuthDomain, false)) {
        errors.rejectValue(
            "webAuthDomain",
            "sep10-web-auth-domain-invalid",
            "The sep10.web_auth_domain does not have valid format.");
      }
    } else if (homeDomains != null
        && !homeDomains.isEmpty()
        && (homeDomains.size() > 1 || homeDomains.get(0).contains("*"))) {
      errors.rejectValue(
          "webAuthDomain",
          "sep10-web-auth-domain-empty",
          "The sep10.web_auth_domain is required for multiple home domains.");
    }

    if (authTimeout <= 0) {
      errors.rejectValue(
          "authTimeout",
          "sep10-auth-timeout-invalid",
          "The sep10.auth_timeout must be greater than 0");
    }

    if (jwtTimeout <= 0) {
      errors.rejectValue(
          "jwtTimeout",
          "sep10-jwt-timeout-invalid",
          "The sep10.jwt_timeout must be greater than 0");
    }
  }

  void validateClientAttribution(Errors errors) {
    if (clientAttributionRequired) {
      List<String> nonCustodialClientNames =
          clientsConfig.clients.stream()
              .filter(cfg -> cfg.getType() == NONCUSTODIAL)
              .map(ClientConfig::getName)
              .collect(Collectors.toList());

      if (nonCustodialClientNames.isEmpty()) {
        errors.reject(
            "sep10-client-attribution-lists-empty",
            "sep10.client_attribution_required is set to true but no NONCUSTODIAL clients are defined in the clients: section of the configuration.");
      }
    }

    // Make sure all the names in the allow list is defined in the clients section.
    if (clientAllowList != null && !clientAllowList.isEmpty()) {
      for (String clientName : clientAllowList) {
        if (clientsConfig.getClientConfigByName(clientName) == null) {
          errors.reject(
              "sep10-client-allow-list-invalid",
              format("Invalid client name:%s in sep10.client_allow_list", clientName));
        }
      }
    }
  }

  void validateCustodialAccounts(Errors errors) {
    for (String account : getKnownCustodialAccountList()) {
      try {
        if (account != null) KeyPair.fromAccountId(account);
      } catch (Throwable ex) {
        errors.reject(
            "sep10-custodial-account-not-valid",
            format("Invalid custodial account:%s in clients:", account));
      }
    }
  }

  private void validateDomain(Errors errors, String domain) {
    try {
      new ManageDataOperation.Builder(String.format("%s %s", domain, "auth"), new byte[64]).build();
    } catch (IllegalArgumentException iaex) {
      errors.rejectValue(
          "homeDomain",
          "sep10-home-domain-too-long",
          format(
              "The sep10.home_domain (%s) is longer than the maximum length (59) of a domain. Error=%s",
              domain, iaex));
    }

    if (!NetUtil.isServerPortValid(domain, false)) {
      errors.rejectValue(
          "homeDomain",
          "sep10-home-domain-invalid",
          "The sep10.home_domain does not have valid format.");
    }
  }

  @Override
  public List<String> getAllowedClientDomains() {
    // if clientAllowList is not defined, all client domains from the clients section are allowed.
    if (clientAllowList == null || clientAllowList.isEmpty()) {
      return clientsConfig.clients.stream()
          .filter(cfg -> cfg.getDomains() != null && !cfg.getDomains().isEmpty())
          .flatMap(cfg -> cfg.getDomains().stream())
          .collect(Collectors.toList());
    }

    // If clientAllowList is defined, only the clients in the allow list are allowed.
    return clientAllowList.stream()
        .filter(name -> clientsConfig.getClientConfigByName(name) != null)
        .filter(name -> clientsConfig.getClientConfigByName(name).getDomains() != null)
        .flatMap(name -> clientsConfig.getClientConfigByName(name).getDomains().stream())
        .filter(StringHelper::isNotEmpty)
        .collect(Collectors.toList());
  }

  @Override
  public List<String> getAllowedClientNames() {
    // if clientAllowList is not defined, all clients from the clients section are allowed.
    if (clientAllowList == null || clientAllowList.isEmpty()) {
      return clientsConfig.clients.stream()
          .map(ClientConfig::getName)
          .filter(StringHelper::isNotEmpty)
          .collect(Collectors.toList());
    }
    return clientAllowList;
  }

  @Override
  public List<String> getKnownCustodialAccountList() {
    return knownCustodialAccountList;
  }
}
