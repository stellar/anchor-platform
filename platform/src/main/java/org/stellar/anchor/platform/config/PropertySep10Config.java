package org.stellar.anchor.platform.config;

import static java.lang.String.format;
import static org.stellar.anchor.util.StringHelper.isEmpty;
import static org.stellar.anchor.util.StringHelper.isNotEmpty;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.client.ClientConfig;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.client.NonCustodialClient;
import org.stellar.anchor.config.SecretConfig;
import org.stellar.anchor.config.Sep10Config;
import org.stellar.anchor.config.StellarNetworkConfig;
import org.stellar.anchor.util.KeyUtil;
import org.stellar.anchor.util.NetUtil;
import org.stellar.sdk.*;
import org.stellar.sdk.operations.ManageDataOperation;

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
  private StellarNetworkConfig stellarNetworkConfig;
  private final ClientService clientService;
  private SecretConfig secretConfig;
  private boolean requireAuthHeader = false;

  public PropertySep10Config(
      StellarNetworkConfig stellarNetworkConfig,
      ClientService clientService,
      SecretConfig secretConfig) {
    this.stellarNetworkConfig = stellarNetworkConfig;
    this.clientService = clientService;
    this.secretConfig = secretConfig;
    this.knownCustodialAccountList =
        clientService.getCustodialClients().stream()
            .flatMap(cfg -> cfg.getSigningKeys().stream())
            .collect(Collectors.toList());
  }

  @PostConstruct
  public void postConstruct() {
    // If webAuthDomain is not specified and there is 1 and only 1 fixed domain in the home_domains
    if (isEmpty(webAuthDomain)) {
      if (homeDomains.size() == 1 && !homeDomains.get(0).contains("*")) {
        webAuthDomain = homeDomains.get(0);
      }
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
          "sep10-signing-seed-empty",
          "Please set the secret secret.sep10.signing_seed or the SECRET_SEP10_SIGNING_SEED environment variable");
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

    if (homeDomains == null || homeDomains.isEmpty()) {
      errors.reject(
          "sep10-home-domains-empty",
          "Please set the sep10.home_domains or SEP10_HOME_DOMAINS environment variable.");
    } else {
      for (String domain : homeDomains) {
        validateDomain(errors, domain);
      }
    }

    if (isNotEmpty(webAuthDomain)) {
      try {
        ManageDataOperation.builder().name(webAuthDomain).value(new byte[64]).build();
      } catch (IllegalArgumentException iaex) {
        errors.rejectValue(
            "webAuthDomain",
            "sep10-web-auth-domain-too-long",
            format(
                "The sep10.web_auth_domain (%s) is longer than the maximum length (59) of a domain. Error=%s",
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
          clientService.getNonCustodialClients().stream().map(ClientConfig::getName).toList();

      if (nonCustodialClientNames.isEmpty()) {
        errors.reject(
            "sep10-client-attribution-lists-empty",
            "sep10.client_attribution_required is set to true but no NONCUSTODIAL clients are defined in the clients: section of the configuration.");
      }
    }

    // Make sure all the names in the allow list is defined in the clients section.
    if (clientAllowList != null && !clientAllowList.isEmpty()) {
      for (String clientName : clientAllowList) {
        if (clientService.getClientConfigByName(clientName) == null) {
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
      ManageDataOperation.builder()
          .name(String.format("%s %s", domain, "auth"))
          .value(new byte[64])
          .build();
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
      return clientService.getNonCustodialClients().stream()
          .flatMap(cfg -> cfg.getDomains().stream())
          .collect(Collectors.toList());
    }

    // If clientAllowList is defined, only the clients in the allow list are allowed.
    return clientAllowList.stream()
        .map(clientService::getClientConfigByName)
        .filter(Objects::nonNull)
        .filter(config -> config instanceof NonCustodialClient)
        .filter(config -> ((NonCustodialClient) config).getDomains() != null)
        .flatMap(config -> ((NonCustodialClient) config).getDomains().stream())
        .collect(Collectors.toList());
  }

  @Override
  public List<String> getAllowedClientNames() {
    // if clientAllowList is not defined, all clients from the clients section are allowed.
    if (clientAllowList == null || clientAllowList.isEmpty()) {
      return clientService.getAllClients().stream()
          .map(ClientConfig::getName)
          .collect(Collectors.toList());
    }
    return clientAllowList;
  }

  @Override
  public List<String> getKnownCustodialAccountList() {
    return knownCustodialAccountList;
  }
}
