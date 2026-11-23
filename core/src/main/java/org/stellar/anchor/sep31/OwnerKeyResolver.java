package org.stellar.anchor.sep31;

import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.client.ClientConfig;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.client.CustodialClient;
import org.stellar.anchor.util.Log;

/**
 * Decides the {@code creator_account} under which a caller owns a customer id.
 *
 * <p>A custodial client authenticates with any of its registered signing keys, so its owner key is
 * the client name alone and the end user is told apart by the owner memo. Every other caller keeps
 * {@link WebAuthJwt#getOwnerKey()}, which includes the account, so end users of a non-custodial
 * wallet never share an owner.
 */
public class OwnerKeyResolver {
  private final ClientService clientService;

  public OwnerKeyResolver(ClientService clientService) {
    this.clientService = clientService;
  }

  public String ownerKey(WebAuthJwt token) {
    return isCustodial(token) ? token.getClientName() : token.getOwnerKey();
  }

  /**
   * Whether the token belongs to a registered custodial client, the one case where {@link
   * #ownerKey} is shared by every end user and the owner memo is the only thing that tells them
   * apart. An unregistered client or a failing registry lookup is not custodial.
   */
  public boolean isCustodial(WebAuthJwt token) {
    String clientName = token.getClientName();
    return clientName != null && isCustodial(clientName);
  }

  private boolean isCustodial(String clientName) {
    try {
      ClientConfig client = clientService.getClientConfigByName(clientName);
      return client instanceof CustodialClient;
    } catch (Exception e) {
      // Fail closed: the per-account key can only refuse a caller, never merge owners.
      Log.warnEx(e);
      return false;
    }
  }
}
