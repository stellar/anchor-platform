package org.stellar.anchor.platform.service;

import java.time.Instant;
import lombok.SneakyThrows;
import org.stellar.anchor.SepTransaction;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.MoreInfoUrlJwt;
import org.stellar.anchor.auth.MoreInfoUrlJwt.*;
import org.stellar.anchor.config.ClientsConfig;
import org.stellar.anchor.platform.config.MoreInfoUrlConfig;
import org.stellar.anchor.platform.config.PropertyClientsConfig;
import org.stellar.anchor.sep6.Sep6Transaction;
import org.stellar.anchor.util.ConfigHelper;

public class Sep6MoreInfoUrlConstructor extends SimpleMoreInfoUrlConstructor {

  public Sep6MoreInfoUrlConstructor(
      PropertyClientsConfig clientsConfig, MoreInfoUrlConfig config, JwtService jwtService) {
    super(clientsConfig, config, jwtService);
  }

  @Override
  public String construct(SepTransaction txn, String lang) {
    Sep6Transaction sep6Txn = (Sep6Transaction) txn;
    if (config == null) {
      return null;
    }
    return construct(
        sep6Txn.getClientDomain(),
        sep6Txn.getSep10Account(),
        sep6Txn.getSep10AccountMemo(),
        sep6Txn.getTransactionId(),
        sep6Txn,
        lang);
  }

  @Override
  @SneakyThrows
  public MoreInfoUrlJwt getBaseToken(
      String clientDomain, String sep10Account, String sep10AccountMemo, String transactionId) {
    ClientsConfig.ClientConfig clientConfig =
        ConfigHelper.getClientConfig(clientsConfig, clientDomain, sep10Account);
    return new Sep6MoreInfoUrlJwt(
        UrlConstructorHelper.getAccount(sep10Account, sep10AccountMemo),
        transactionId,
        Instant.now().getEpochSecond() + config.getJwtExpiration(),
        clientDomain,
        clientConfig != null ? clientConfig.getName() : null);
  }
}
