package org.stellar.anchor.platform.config;

import static io.jsonwebtoken.lang.Collections.setOf;
import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.PostConstruct;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.stellar.anchor.config.ClientsConfig;

@Data
public class PropertyClientsConfig implements ClientsConfig, Validator {
  List<ClientConfig> clients = Lists.newLinkedList();
  Map<String, ClientConfig> clientMap = null;
  Map<String, String> domainToClientNameMap = null;
  Map<String, String> signingKeyToClientNameMap = null;

  @PostConstruct
  public void postConstruct() {
    // In favor of migrating to signingKeys and domains, copy signingKey to signingKeys and domain
    // to domains if signingKeys and domains are not set.
    clients.forEach(
        clientConfig -> {
          if (!isEmpty(clientConfig.getSigningKey())) {
            clientConfig.setSigningKeys(setOf(clientConfig.getSigningKey()));
          }
          if (!isEmpty(clientConfig.getDomain())) {
            clientConfig.setDomains(setOf(clientConfig.getDomain()));
          }
        });
  }

  @Override
  public ClientsConfig.ClientConfig getClientConfigBySigningKey(String signingKey) {
    if (signingKeyToClientNameMap == null) {
      signingKeyToClientNameMap = Maps.newHashMap();
      clients.forEach(
          clientConfig -> {
            if (clientConfig.getSigningKeys() != null && !clientConfig.getSigningKeys().isEmpty()) {
              for (String key : clientConfig.getSigningKeys()) {
                signingKeyToClientNameMap.put(key, clientConfig.getName());
              }
            }
          });
    }
    return getClientConfigByName(signingKeyToClientNameMap.get(signingKey));
  }

  @Override
  public ClientConfig getClientConfigByDomain(String domain) {
    if (domainToClientNameMap == null) {
      domainToClientNameMap = Maps.newHashMap();
      clients.forEach(
          clientConfig -> {
            if (clientConfig.getDomains() != null && !clientConfig.getDomains().isEmpty()) {
              for (String domainName : clientConfig.getDomains()) {
                domainToClientNameMap.put(domainName, clientConfig.getName());
              }
            }
          });
    }
    return getClientConfigByName(domainToClientNameMap.get(domain));
  }

  public ClientConfig getClientConfigByName(String name) {
    if (clientMap == null) {
      clientMap = Maps.newHashMap();
      clients.forEach(clientConfig -> clientMap.put(clientConfig.getName(), clientConfig));
    }
    return clientMap.get(name);
  }

  @Override
  public boolean supports(@NotNull Class<?> clazz) {
    return PropertyClientsConfig.class.isAssignableFrom(clazz);
  }

  @Override
  public void validate(@NotNull Object target, @NotNull Errors errors) {
    PropertyClientsConfig configs = (PropertyClientsConfig) target;
    configs.clients.forEach(clientConfig -> validateClient(clientConfig, errors));
  }

  private void validateClient(ClientConfig clientConfig, Errors errors) {
    debugF("Validating client {}", clientConfig);
    if (isEmpty(clientConfig.getName())) {
      errors.reject("empty-client-name", "The client.name cannot be empty and must be defined");
    }
    if (clientConfig.getType().equals(ClientType.CUSTODIAL)) {
      validateCustodialClient(clientConfig, errors);
    } else {
      validateNonCustodialClient(clientConfig, errors);
    }
  }

  public void validateCustodialClient(ClientConfig clientConfig, Errors errors) {
    if (isEmpty(clientConfig.getSigningKey())
        && (clientConfig.getSigningKeys() == null || clientConfig.getSigningKeys().isEmpty())) {
      errors.reject(
          "empty-client-signing-keys",
          "The client.signingKeys cannot be empty and must be defined");
    }
    if (!isEmpty(clientConfig.getSigningKey())
        && clientConfig.getSigningKeys() != null
        && !clientConfig.getSigningKeys().isEmpty()) {
      errors.reject(
          "client-signing-keys-conflict",
          "The client.signingKey and The client.signingKeys cannot coexist, please choose one to use");
    }

    validateCallbackUrls(clientConfig, errors);
  }

  public void validateNonCustodialClient(ClientConfig clientConfig, Errors errors) {
    if (isEmpty(clientConfig.getDomain())
        && (clientConfig.getDomains() == null || clientConfig.getDomains().isEmpty())) {
      errors.reject(
          "empty-client-domains", "The client.domains cannot be empty and must be defined");
    }
    if (!isEmpty(clientConfig.getDomain())
        && clientConfig.getDomains() != null
        && !clientConfig.getDomains().isEmpty()) {
      errors.reject(
          "client-domains-conflict",
          "The client.domain and the client.domains cannot coexist, please choose one to use");
    }

    validateCallbackUrls(clientConfig, errors);

    if (clientConfig.getDestinationAccounts() != null) {
      errors.reject(
          "destination-accounts-noncustodial",
          "Destination accounts list is not a valid configuration option for a non-custodial client");
    }
  }

  void validateCallbackUrls(ClientConfig client, Errors errors) {
    ImmutableMap.of(
            "callback_url",
            Optional.ofNullable(client.getCallbackUrl()).orElse(""),
            "callback_url_sep6",
            Optional.ofNullable(client.getCallbackUrlSep6()).orElse(""),
            "callback_url_sep24",
            Optional.ofNullable(client.getCallbackUrlSep24()).orElse(""),
            "callback_url_sep31",
            Optional.ofNullable(client.getCallbackUrlSep31()).orElse(""),
            "callback_url_sep12",
            Optional.ofNullable(client.getCallbackUrlSep12()).orElse(""))
        .forEach(
            (key, value) -> {
              if (!isEmpty(value)) {
                try {
                  new URL(value);
                } catch (MalformedURLException e) {
                  errors.reject("client-invalid-" + key, "The client." + key + " is invalid");
                }
              }
            });
  }
}
