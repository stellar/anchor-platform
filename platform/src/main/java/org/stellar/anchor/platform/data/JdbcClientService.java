package org.stellar.anchor.platform.data;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.stellar.anchor.client.ClientConfig;
import org.stellar.anchor.client.ClientConfig.CallbackUrls;
import org.stellar.anchor.client.ClientConfig.ClientType;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.client.CustodialClient;
import org.stellar.anchor.client.NonCustodialClient;

@RequiredArgsConstructor
public class JdbcClientService implements ClientService {
  private final JdbcClientConfigRepo repo;

  @Override
  public List<ClientConfig> getAllClients() {
    List<ClientConfig> clients = new ArrayList<>();
    repo.findAll().forEach(c -> clients.add(toClientConfig(c)));
    return clients;
  }

  @Override
  public List<CustodialClient> getCustodialClients() {
    return repo.findByType(ClientType.CUSTODIAL).stream()
        .map(this::toCustodialClient)
        .collect(Collectors.toList());
  }

  @Override
  public List<NonCustodialClient> getNonCustodialClients() {
    return repo.findByType(ClientType.NONCUSTODIAL).stream()
        .map(this::toNonCustodialClient)
        .collect(Collectors.toList());
  }

  @Override
  public ClientConfig getClientConfigByName(String clientName) {
    return repo.findById(clientName).map(this::toClientConfig).orElse(null);
  }

  @Override
  public CustodialClient getClientConfigBySigningKey(String signingKey) {
    JdbcClientConfig found = repo.findBySigningKey(signingKey);
    return found == null ? null : toCustodialClient(found);
  }

  @Override
  public NonCustodialClient getClientConfigByDomain(String domain) {
    JdbcClientConfig found = repo.findByDomain(domain);
    return found == null ? null : toNonCustodialClient(found);
  }

  @Override
  public ClientConfig getClientConfigByDomainAndAccount(String domain, String account) {
    ClientConfig clientByDomain = getClientConfigByDomain(domain);
    ClientConfig clientByAccount = getClientConfigBySigningKey(account);
    return clientByDomain != null ? clientByDomain : clientByAccount;
  }

  private ClientConfig toClientConfig(JdbcClientConfig c) {
    return c.getType() == ClientType.CUSTODIAL ? toCustodialClient(c) : toNonCustodialClient(c);
  }

  private CustodialClient toCustodialClient(JdbcClientConfig c) {
    return CustodialClient.builder()
        .name(c.getName())
        .signingKeys(c.getSigningKeys())
        .callbackUrls(toCallbackUrls(c))
        .allowAnyDestination(c.isAllowAnyDestination())
        .destinationAccounts(c.getDestinationAccounts())
        .build();
  }

  private NonCustodialClient toNonCustodialClient(JdbcClientConfig c) {
    return NonCustodialClient.builder()
        .name(c.getName())
        .domains(c.getDomains())
        .callbackUrls(toCallbackUrls(c))
        .build();
  }

  private CallbackUrls toCallbackUrls(JdbcClientConfig c) {
    return CallbackUrls.builder()
        .sep6(c.getCallbackUrlSep6())
        .sep24(c.getCallbackUrlSep24())
        .sep31(c.getCallbackUrlSep31())
        .sep12(c.getCallbackUrlSep12())
        .build();
  }
}
