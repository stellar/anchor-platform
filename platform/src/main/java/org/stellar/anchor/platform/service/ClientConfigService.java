package org.stellar.anchor.platform.service;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.NotFoundException;
import org.stellar.anchor.client.ClientConfig.CallbackUrls;
import org.stellar.anchor.client.ClientConfig.ClientType;
import org.stellar.anchor.platform.controller.platform.ClientConfigRequest;
import org.stellar.anchor.platform.controller.platform.ClientConfigResponse;
import org.stellar.anchor.platform.data.JdbcClientConfig;
import org.stellar.anchor.platform.data.JdbcClientConfigRepo;

@RequiredArgsConstructor
public class ClientConfigService {
  private final JdbcClientConfigRepo repo;

  public ClientConfigResponse upsert(String name, ClientConfigRequest request)
      throws BadRequestException {
    validate(name, request);

    JdbcClientConfig entity = repo.findById(name).orElseGet(JdbcClientConfig::new);
    entity.setName(name);
    entity.setType(request.getType());
    entity.setAllowAnyDestination(request.isAllowAnyDestination());
    entity.setDomains(nullToEmpty(request.getDomains()));
    entity.setSigningKeys(nullToEmpty(request.getSigningKeys()));
    entity.setDestinationAccounts(nullToEmpty(request.getDestinationAccounts()));
    if (request.getCallbackUrls() != null) {
      entity.setCallbackUrlSep6(request.getCallbackUrls().getSep6());
      entity.setCallbackUrlSep24(request.getCallbackUrls().getSep24());
      entity.setCallbackUrlSep31(request.getCallbackUrls().getSep31());
      entity.setCallbackUrlSep12(request.getCallbackUrls().getSep12());
    }

    try {
      return toResponse(repo.save(entity));
    } catch (DataIntegrityViolationException e) {
      throw new BadRequestException("domain or signing key is already in use by another client");
    }
  }

  public ClientConfigResponse get(String name) throws NotFoundException {
    return repo.findById(name)
        .map(this::toResponse)
        .orElseThrow(() -> new NotFoundException(String.format("Client %s not found", name)));
  }

  public List<ClientConfigResponse> list() {
    return java.util.stream.StreamSupport.stream(repo.findAll().spliterator(), false)
        .map(this::toResponse)
        .toList();
  }

  public void delete(String name) throws NotFoundException {
    if (!repo.existsById(name)) {
      throw new NotFoundException(String.format("Client %s not found", name));
    }
    repo.deleteById(name);
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
    validateCallbackUrls(request.getCallbackUrls());
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
        try {
          new URL(url);
        } catch (MalformedURLException e) {
          throw new BadRequestException(String.format("Invalid callback URL: %s", url));
        }
      }
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
