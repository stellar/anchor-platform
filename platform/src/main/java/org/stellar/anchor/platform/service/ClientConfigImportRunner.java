package org.stellar.anchor.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.stellar.anchor.config.ClientsConfig;
import org.stellar.anchor.config.ClientsConfig.RawClient;
import org.stellar.anchor.platform.controller.platform.ClientConfigRequest;
import org.stellar.anchor.util.Log;

@RequiredArgsConstructor
public class ClientConfigImportRunner implements CommandLineRunner {
  private final ClientsConfig clientsConfig;
  private final ClientConfigService clientConfigService;

  @Override
  public void run(String... args) {
    int imported = 0;
    for (RawClient item : clientsConfig.getItems()) {
      try {
        clientConfigService.upsert(item.getName(), toRequest(item));
        imported++;
      } catch (Exception e) {
        Log.errorEx(
            String.format("Failed to import client [%s] into the database", item.getName()), e);
      }
    }
    Log.infoF(
        "Imported {} of {} clients into the database", imported, clientsConfig.getItems().size());
  }

  private ClientConfigRequest toRequest(RawClient item) {
    ClientConfigRequest request = new ClientConfigRequest();
    request.setType(item.getType());
    request.setDomains(item.getDomains());
    request.setSigningKeys(item.getSigningKeys());
    request.setCallbackUrls(item.getCallbackUrls());
    request.setAllowAnyDestination(item.isAllowAnyDestination());
    request.setDestinationAccounts(item.getDestinationAccounts());
    return request;
  }
}
