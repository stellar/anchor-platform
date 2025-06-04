package org.stellar.anchor.client;

import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stellar.anchor.api.exception.SepNotAuthorizedException;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.config.Sep10Config;

/** Finds the client name for a SEP-10 JWT. */
@RequiredArgsConstructor
public class ClientFinder {
  @NonNull private final Sep10Config sep10Config;
  @NonNull private final ClientService clientService;

  /**
   * Returns the client name for a pair of client domain/account. If the client attribution is not
   * required, the client ID is returned if the client is found. If the client attribution is
   * required, the client ID is returned if the client is found and the client domain and name are
   * allowed.
   *
   * @param clientDomain client domain
   * @param account account
   * @return the client ID
   * @throws SepNotAuthorizedException if the client is not found or the client domain or name is
   *     not
   */
  @Nullable
  public String getClientName(String clientDomain, String account)
      throws SepNotAuthorizedException {
    ClientConfig client = getClient(clientDomain, account);

    // If client attribution is not required, return the client name
    if (!sep10Config.isClientAttributionRequired()) {
      return client != null ? client.getName() : null;
    }

    // Check if the client is allowed
    if (client == null) {
      throw new SepNotAuthorizedException("Client not found");
    }

    if (!sep10Config.getAllowedClientNames().contains(client.getName())) {
      throw new SepNotAuthorizedException("Client name not allowed");
    }

    return client.getName();
  }

  @Nullable
  public String getClientName(WebAuthJwt token) throws SepNotAuthorizedException {
    return getClientName(token.getClientDomain(), token.getAccount());
  }

  @Nullable
  private ClientConfig getClient(String clientDomain, String account) {
    ClientConfig clientByDomain = clientService.getClientConfigByDomain(clientDomain);
    ClientConfig clientByAccount = clientService.getClientConfigBySigningKey(account);
    return clientByDomain != null ? clientByDomain : clientByAccount;
  }
}
