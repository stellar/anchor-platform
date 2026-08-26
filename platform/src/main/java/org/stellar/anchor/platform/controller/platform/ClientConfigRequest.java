package org.stellar.anchor.platform.controller.platform;

import java.util.Set;
import lombok.Data;
import org.stellar.anchor.client.ClientConfig.CallbackUrls;
import org.stellar.anchor.client.ClientConfig.ClientType;

@Data
public class ClientConfigRequest {
  private ClientType type;
  private Set<String> domains;
  private Set<String> signingKeys;
  private CallbackUrls callbackUrls;
  private boolean allowAnyDestination;
  private Set<String> destinationAccounts;
}
