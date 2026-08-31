package org.stellar.anchor.platform.service;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.NotFoundException;
import org.stellar.anchor.client.ClientConfig.CallbackUrls;
import org.stellar.anchor.client.ClientConfig.ClientType;
import org.stellar.anchor.platform.controller.platform.ClientConfigRequest;
import org.stellar.anchor.platform.controller.platform.ClientConfigResponse;
import org.stellar.anchor.platform.data.JdbcClientConfig;
import org.stellar.anchor.platform.data.JdbcClientConfigRepo;
import org.stellar.sdk.KeyPair;

@RequiredArgsConstructor
public class ClientConfigService {
  private static final Set<String> UNIQUE_CONSTRAINT_NAMES =
      Set.of("idx_client_domain_domain", "idx_client_signing_key_key");

  private final ObjectProvider<JdbcClientConfigRepo> repoProvider;

  private JdbcClientConfigRepo repo() {
    return repoProvider.getObject();
  }

  public ClientConfigResponse upsert(String name, ClientConfigRequest request)
      throws BadRequestException {
    validate(name, request);

    JdbcClientConfig entity = repo().findById(name).orElseGet(JdbcClientConfig::new);
    entity.setName(name);
    entity.setType(request.getType());
    entity.setAllowAnyDestination(request.isAllowAnyDestination());
    entity.setDomains(nullToEmpty(request.getDomains()));
    entity.setSigningKeys(nullToEmpty(request.getSigningKeys()));
    entity.setDestinationAccounts(nullToEmpty(request.getDestinationAccounts()));
    CallbackUrls callbackUrls = request.getCallbackUrls();
    entity.setCallbackUrlSep6(callbackUrls == null ? null : callbackUrls.getSep6());
    entity.setCallbackUrlSep24(callbackUrls == null ? null : callbackUrls.getSep24());
    entity.setCallbackUrlSep31(callbackUrls == null ? null : callbackUrls.getSep31());
    entity.setCallbackUrlSep12(callbackUrls == null ? null : callbackUrls.getSep12());

    return saveHandlingConflict(entity);
  }

  public ClientConfigResponse addDestinationAccount(String name, String account)
      throws NotFoundException, BadRequestException {
    validateStellarAccount(account, "destination account");
    JdbcClientConfig entity = findOrThrow(name);
    entity.getDestinationAccounts().add(account);
    return saveHandlingConflict(entity);
  }

  public void removeDestinationAccount(String name, String account) throws NotFoundException {
    JdbcClientConfig entity = findOrThrow(name);
    if (!entity.getDestinationAccounts().remove(account)) {
      throw new NotFoundException(
          String.format("Destination account %s not found on client %s", account, name));
    }
    repo().save(entity);
  }

  public ClientConfigResponse addSigningKey(String name, String signingKey)
      throws NotFoundException, BadRequestException {
    validateStellarAccount(signingKey, "signing key");
    JdbcClientConfig entity = findOrThrow(name);
    entity.getSigningKeys().add(signingKey);
    return saveHandlingConflict(entity);
  }

  public void removeSigningKey(String name, String signingKey)
      throws NotFoundException, BadRequestException {
    JdbcClientConfig entity = findOrThrow(name);
    if (entity.getType() == ClientType.CUSTODIAL
        && entity.getSigningKeys().size() == 1
        && entity.getSigningKeys().contains(signingKey)) {
      throw new BadRequestException("Custodial clients must have at least one signing key");
    }
    if (!entity.getSigningKeys().remove(signingKey)) {
      throw new NotFoundException(
          String.format("Signing key %s not found on client %s", signingKey, name));
    }
    repo().save(entity);
  }

  private JdbcClientConfig findOrThrow(String name) throws NotFoundException {
    return repo()
        .findById(name)
        .orElseThrow(() -> new NotFoundException(String.format("Client %s not found", name)));
  }

  private ClientConfigResponse saveHandlingConflict(JdbcClientConfig entity)
      throws BadRequestException {
    try {
      return toResponse(repo().save(entity));
    } catch (DataIntegrityViolationException e) {
      if (isUniqueConstraintViolation(e)) {
        throw new BadRequestException("domain or signing key is already in use by another client");
      }
      throw e;
    }
  }

  private boolean isUniqueConstraintViolation(DataIntegrityViolationException e) {
    String message = e.getMostSpecificCause().getMessage();
    return message != null && UNIQUE_CONSTRAINT_NAMES.stream().anyMatch(message::contains);
  }

  public ClientConfigResponse get(String name) throws NotFoundException {
    return repo()
        .findById(name)
        .map(this::toResponse)
        .orElseThrow(() -> new NotFoundException(String.format("Client %s not found", name)));
  }

  public List<ClientConfigResponse> list() {
    return java.util.stream.StreamSupport.stream(repo().findAll().spliterator(), false)
        .map(this::toResponse)
        .toList();
  }

  public List<ClientConfigResponse> listCustodial() {
    return repo().findByType(ClientType.CUSTODIAL).stream().map(this::toResponse).toList();
  }

  public List<ClientConfigResponse> listNonCustodial() {
    return repo().findByType(ClientType.NONCUSTODIAL).stream().map(this::toResponse).toList();
  }

  public void delete(String name) throws NotFoundException {
    if (!repo().existsById(name)) {
      throw new NotFoundException(String.format("Client %s not found", name));
    }
    repo().deleteById(name);
  }

  private void validate(String name, ClientConfigRequest request) throws BadRequestException {
    if (StringUtils.isBlank(name)) {
      throw new BadRequestException("Client name must not be blank");
    }
    if (request.getType() == null) {
      throw new BadRequestException("Client type must be either custodial or noncustodial");
    }
    if (request.getType() == ClientType.CUSTODIAL
        && (request.getSigningKeys() == null || request.getSigningKeys().isEmpty())) {
      throw new BadRequestException("Custodial clients must have at least one signing key");
    }
    if (request.getType() == ClientType.NONCUSTODIAL
        && (request.getDomains() == null || request.getDomains().isEmpty())) {
      throw new BadRequestException("Noncustodial clients must have at least one domain");
    }
    if (request.getSigningKeys() != null) {
      for (String signingKey : request.getSigningKeys()) {
        validateStellarAccount(signingKey, "signing key");
      }
    }
    if (request.getDestinationAccounts() != null) {
      for (String account : request.getDestinationAccounts()) {
        validateStellarAccount(account, "destination account");
      }
    }
    validateCallbackUrls(request.getCallbackUrls());
  }

  private void validateStellarAccount(String account, String fieldLabel)
      throws BadRequestException {
    try {
      KeyPair.fromAccountId(account);
    } catch (Exception e) {
      throw new BadRequestException(String.format("Invalid %s: %s", fieldLabel, account));
    }
  }

  private void validateCallbackUrls(CallbackUrls callbackUrls) throws BadRequestException {
    if (callbackUrls == null) {
      return;
    }
    String[] urls = {
      callbackUrls.getSep6(),
      callbackUrls.getSep24(),
      callbackUrls.getSep31(),
      callbackUrls.getSep12()
    };
    for (String url : urls) {
      if (StringUtils.isNotEmpty(url)) {
        validateHttpUrl(url);
      }
    }
  }

  private void validateHttpUrl(String url) throws BadRequestException {
    URL parsed;
    try {
      parsed = new URL(url);
    } catch (MalformedURLException e) {
      throw new BadRequestException(String.format("Invalid callback URL: %s", url));
    }
    if (!"http".equalsIgnoreCase(parsed.getProtocol())
        && !"https".equalsIgnoreCase(parsed.getProtocol())) {
      throw new BadRequestException(String.format("Invalid callback URL: %s", url));
    }
  }

  private Set<String> nullToEmpty(Set<String> values) {
    return values == null ? new HashSet<>() : new HashSet<>(values);
  }

  private ClientConfigResponse toResponse(JdbcClientConfig entity) {
    return ClientConfigResponse.builder()
        .name(entity.getName())
        .type(entity.getType())
        .domains(entity.getDomains())
        .signingKeys(entity.getSigningKeys())
        .destinationAccounts(entity.getDestinationAccounts())
        .allowAnyDestination(entity.isAllowAnyDestination())
        .callbackUrls(
            CallbackUrls.builder()
                .sep6(entity.getCallbackUrlSep6())
                .sep24(entity.getCallbackUrlSep24())
                .sep31(entity.getCallbackUrlSep31())
                .sep12(entity.getCallbackUrlSep12())
                .build())
        .build();
  }
}
