package org.stellar.anchor.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.util.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.SepNotFoundException;
import org.stellar.anchor.config.ClientsConfig;
import org.stellar.anchor.config.ClientsConfig.RawClient;
import org.stellar.anchor.util.FileUtil;
import org.stellar.anchor.util.GsonUtils;
import org.yaml.snakeyaml.Yaml;

@Getter
@NoArgsConstructor
public class DefaultClientService implements ClientService {
  static final Gson gson = GsonUtils.getInstance();
  List<CustodialClient> custodialClients = new ArrayList<>();
  List<NonCustodialClient> nonCustodialClients = new ArrayList<>();

  /**
   * Creates a DefaultClientService instance based on the provided ClientsConfig.
   *
   * @param clientsConfig the configuration object containing client setup details
   * @return a DefaultClientService instance configured according to the provided ClientsConfig
   */
  public static DefaultClientService fromClientsConfig(ClientsConfig clientsConfig) {
    return createDCSFromItemsList(clientsConfig.getItems());
  }

  public static DefaultClientService createDCSFromItemsList(List<RawClient> items) {
    DefaultClientService dcs = new DefaultClientService();
    for (RawClient client : items) {
      ClientConfig.ClientType type = client.getType();
      if (type.equals(ClientConfig.ClientType.CUSTODIAL)) {
        dcs.custodialClients.add(client.toCustodialClient());
      } else {
        dcs.nonCustodialClients.add(client.toNonCustodialClient());
      }
    }
    return dcs;
  }

  public static DefaultClientService createDCSFromMap(Map<String, List<Object>> map) {
    map.get("items").removeIf(Objects::isNull);
    List<RawClient> clientList =
        gson.fromJson(gson.toJson(map.get("items")), new TypeToken<List<RawClient>>() {}.getType());
    return createDCSFromItemsList(clientList);
  }

  public static DefaultClientService fromYamlResourceFile(String yamlResourceFilePath)
      throws IOException, SepNotFoundException, InvalidConfigException {
    String resource = FileUtil.getResourceFileAsString(yamlResourceFilePath);
    Map<String, List<Object>> map = new Yaml().load(resource);
    return createDCSFromMap(map);
  }

  public static DefaultClientService fromJsonResourceFile(String jsonResourceFilePath)
      throws IOException, SepNotFoundException, InvalidConfigException {
    String resource = FileUtil.getResourceFileAsString(jsonResourceFilePath);
    Map<String, List<Object>> map = gson.fromJson(resource, Map.class);
    return createDCSFromMap(map);
  }

  public ClientConfig getClientConfigByName(String name) {
    for (CustodialClient client : custodialClients) {
      if (client.getName().equals(name)) {
        return client;
      }
    }
    for (NonCustodialClient client : nonCustodialClients) {
      if (client.getName().equals(name)) {
        return client;
      }
    }

    return null;
  }

  @Override
  public List<ClientConfig> getAllClients() {
    List<ClientConfig> clients = new ArrayList<>();
    if (custodialClients != null) {
      clients.addAll(custodialClients);
    }
    if (nonCustodialClients != null) {
      clients.addAll(nonCustodialClients);
    }
    return clients;
  }

  @Override
  public CustodialClient getClientConfigBySigningKey(String signingKey) {
    for (CustodialClient client : custodialClients) {
      if (client.getSigningKeys() != null && client.getSigningKeys().contains(signingKey)) {
        return client;
      }
    }
    return null;
  }

  @Override
  public NonCustodialClient getClientConfigByDomain(String domain) {
    for (NonCustodialClient client : nonCustodialClients) {
      if (client.getDomains() != null && client.getDomains().contains(domain)) {
        return client;
      }
    }
    return null;
  }

  @Override
  public ClientConfig getClientConfigByDomainAndAccount(String domain, String account) {
    ClientConfig clientByDomain = getClientConfigByDomain(domain);
    ClientConfig clientByAccount = getClientConfigBySigningKey(account);
    return clientByDomain != null ? clientByDomain : clientByAccount;
  }
}
