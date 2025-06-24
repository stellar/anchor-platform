package org.stellar.anchor.client;

import java.util.List;

public interface ClientService {
  List<ClientConfig> getAllClients();

  List<CustodialClient> getCustodialClients();

  List<NonCustodialClient> getNonCustodialClients();

  ClientConfig getClientConfigByName(String clientName);

  CustodialClient getClientConfigBySigningKey(String signingKey);

  NonCustodialClient getClientConfigByDomain(String domain);

  ClientConfig getClientConfigByDomainAndAccount(String domain, String account);
}
