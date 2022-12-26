package org.stellar.anchor.platform.service;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import org.apache.http.client.utils.URIBuilder;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.JwtToken;
import org.stellar.anchor.platform.config.PropertySep24Config;
import org.stellar.anchor.sep24.MoreInfoUrlConstructor;
import org.stellar.anchor.sep24.Sep24Transaction;

public class SimpleMoreInfoUrlConstructor extends MoreInfoUrlConstructor {
  private final PropertySep24Config.SimpleMoreInfoUrlConfig config;
  private final JwtService jwtService;

  public SimpleMoreInfoUrlConstructor(
      PropertySep24Config.SimpleMoreInfoUrlConfig config, JwtService jwtService) {
    this.config = config;
    this.jwtService = jwtService;
  }

  @Override
  public String construct(Sep24Transaction txn) throws URISyntaxException, MalformedURLException {
    JwtToken token =
        JwtToken.of(
            "moreInfoUrl",
            txn.getSep10Account(),
            Instant.now().getEpochSecond(),
            Instant.now().getEpochSecond() + config.getJwtExpiration(),
            txn.getTransactionId(),
            txn.getClientDomain());

    // TODO: Fix the more_info_url
    URI uri = new URI(config.getBaseUrl());

    URIBuilder builder =
        new URIBuilder()
            .setScheme(uri.getScheme())
            .setHost(uri.getHost())
            .setPort(uri.getPort())
            .setPath("transaction-status")
            .addParameter("transaction_id", txn.getTransactionId())
            .addParameter("token", jwtService.encode(token));

    return builder.build().toURL().toString();
  }
}
