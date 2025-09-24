package org.stellar.anchor.platform.service;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import lombok.SneakyThrows;
import org.apache.http.client.utils.URIBuilder;
import org.stellar.anchor.MoreInfoUrlConstructor;
import org.stellar.anchor.SepTransaction;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.MoreInfoUrlJwt;
import org.stellar.anchor.platform.config.MoreInfoUrlConfig;
import org.stellar.anchor.platform.config.PropertyClientsConfig;

public abstract class SimpleMoreInfoUrlConstructor implements MoreInfoUrlConstructor {
  PropertyClientsConfig clientsConfig;
  final MoreInfoUrlConfig config;
  private final JwtService jwtService;

  public SimpleMoreInfoUrlConstructor(
      PropertyClientsConfig clientsConfig, MoreInfoUrlConfig config, JwtService jwtService) {
    this.clientsConfig = clientsConfig;
    this.config = config;
    this.jwtService = jwtService;
  }

  public abstract String construct(SepTransaction txn, String lang);

  @SneakyThrows
  public String construct(
      String clientDomain,
      String memo,
      String sep10Account,
      String transactionId,
      SepTransaction txn,
      String lang) {

    MoreInfoUrlJwt token = getBaseToken(clientDomain, memo, sep10Account, transactionId);

    // add lang to token
    Map<String, String> data = new HashMap<>();
    data.put("lang", lang);
    // add txn_fields to token
    UrlConstructorHelper.addTxnFields(data, txn, config.getTxnFields());
    token.claim("data", data);

    // build url
    String baseUrl = config.getBaseUrl();
    URI uri = new URI(baseUrl);
    return new URIBuilder()
        .setScheme(uri.getScheme())
        .setHost(uri.getHost())
        .setPort(uri.getPort())
        .setPath(uri.getPath())
        .addParameter("transaction_id", transactionId)
        .addParameter("token", jwtService.encode(token))
        .build()
        .toURL()
        .toString();
  }

  public abstract MoreInfoUrlJwt getBaseToken(
      String clientDomain, String sep10Account, String sep10AccountMemo, String transactionId);
}
