package org.stellar.anchor.platform.service;

import static org.stellar.anchor.platform.config.PropertySep24Config.MoreInfoUrlConfig;
import static org.stellar.anchor.util.StringHelper.camelToSnake;
import static org.stellar.anchor.util.StringHelper.snakeToCamelCase;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.SneakyThrows;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.http.client.utils.URIBuilder;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.Sep24MoreInfoUrlJwt;
import org.stellar.anchor.sep24.MoreInfoUrlConstructor;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.util.StringHelper;

public class SimpleMoreInfoUrlConstructor extends MoreInfoUrlConstructor {
  private final MoreInfoUrlConfig config;
  private final JwtService jwtService;

  public SimpleMoreInfoUrlConstructor(MoreInfoUrlConfig config, JwtService jwtService) {
    this.config = config;
    this.jwtService = jwtService;
  }

  @Override
  @SneakyThrows
  public String construct(Sep24Transaction txn) throws URISyntaxException, MalformedURLException {
    Sep24MoreInfoUrlJwt token =
        new Sep24MoreInfoUrlJwt(
            txn.getTransactionId(), Instant.now().getEpochSecond() + config.getJwtExpiration());

    Map<String, String> data = new HashMap<>();

    // Add fields defined in txnFields
    for (String field : config.getTxnFields()) {
      try {
        field = camelToSnake(field);
        String value = BeanUtils.getProperty(txn, snakeToCamelCase(field));
        if (!StringHelper.isEmpty((value))) {
          data.put(field, value);
        }
      } catch (Exception e) {
        // give up
      }
    }

    String tokenCipher = jwtService.encode(token);
    String baseUrl = config.getBaseUrl();
    URI uri = new URI(baseUrl);
    return new URIBuilder()
        .setScheme(uri.getScheme())
        .setHost(uri.getHost())
        .setPort(uri.getPort())
        .setPath(uri.getPath())
        .addParameter("transaction_id", txn.getTransactionId())
        .addParameter("token", tokenCipher)
        .build()
        .toURL()
        .toString();
  }
}
