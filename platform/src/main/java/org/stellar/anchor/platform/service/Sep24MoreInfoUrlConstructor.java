package org.stellar.anchor.platform.service;

import java.time.Instant;
import lombok.SneakyThrows;
import org.stellar.anchor.SepTransaction;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.MoreInfoUrlJwt;
import org.stellar.anchor.auth.MoreInfoUrlJwt.*;
import org.stellar.anchor.client.ClientConfig;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.platform.config.MoreInfoUrlConfig;
import org.stellar.anchor.sep24.Sep24Transaction;

public class Sep24MoreInfoUrlConstructor extends SimpleMoreInfoUrlConstructor {
  public Sep24MoreInfoUrlConstructor(
      AssetService asserService,
      ClientService clientsService,
      MoreInfoUrlConfig config,
      JwtService jwtService) {
    super(asserService, clientsService, config, jwtService);
  }

  @Override
  public String construct(SepTransaction txn, String lang) {
    Sep24Transaction sep24Txn = (Sep24Transaction) txn;
    return construct(
        sep24Txn.getClientDomain(),
        sep24Txn.getWebAuthAccount(),
        sep24Txn.getWebAuthAccountMemo(),
        sep24Txn.getTransactionId(),
        sep24Txn,
        lang);
  }

  @Override
  @SneakyThrows
  public MoreInfoUrlJwt getBaseToken(
      String clientDomain, String webAuthAccount, String webAuthAccountMemo, String transactionId) {
    ClientConfig clientConfig =
        clientsService.getClientConfigByDomainAndAccount(clientDomain, webAuthAccount);
    return new Sep24MoreInfoUrlJwt(
        UrlConstructorHelper.getAccount(webAuthAccount, webAuthAccountMemo),
        transactionId,
        Instant.now().getEpochSecond() + config.getJwtExpiration(),
        clientDomain,
        clientConfig != null ? clientConfig.getName() : null);
  }
}
