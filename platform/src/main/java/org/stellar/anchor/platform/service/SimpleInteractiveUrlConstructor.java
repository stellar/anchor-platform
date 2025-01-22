package org.stellar.anchor.platform.service;

import static org.stellar.anchor.auth.JwtService.HOME_DOMAIN;
import static org.stellar.anchor.platform.service.UrlConstructorHelper.addTxnFields;
import static org.stellar.anchor.sep24.Sep24Service.INTERACTIVE_URL_JWT_REQUIRED_FIELDS_FROM_REQUEST;
import static org.stellar.anchor.sep9.Sep9Fields.extractSep9Fields;
import static org.stellar.anchor.util.Log.debugF;

import com.google.gson.Gson;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.SneakyThrows;
import org.apache.http.client.utils.URIBuilder;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.callback.CustomerIntegration;
import org.stellar.anchor.api.callback.PutCustomerRequest;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.auth.JwtService;
import org.stellar.anchor.auth.Sep24InteractiveUrlJwt;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.client.ClientConfig;
import org.stellar.anchor.client.ClientService;
import org.stellar.anchor.platform.config.PropertySep24Config;
import org.stellar.anchor.sep24.InteractiveUrlConstructor;
import org.stellar.anchor.sep24.Sep24Transaction;
import org.stellar.anchor.util.GsonUtils;

public class SimpleInteractiveUrlConstructor extends InteractiveUrlConstructor {
  public static final String FORWARD_KYC_CUSTOMER_TYPE = "sep24-customer";
  private final AssetService assetService;
  private final ClientService clientsService;
  private final PropertySep24Config sep24Config;
  private final CustomerIntegration customerIntegration;
  private final JwtService jwtService;

  public SimpleInteractiveUrlConstructor(
      AssetService assetService,
      ClientService clientsService,
      PropertySep24Config sep24Config,
      CustomerIntegration customerIntegration,
      JwtService jwtService) {
    this.assetService = assetService;
    this.clientsService = clientsService;
    this.sep24Config = sep24Config;
    this.customerIntegration = customerIntegration;
    this.jwtService = jwtService;
  }

  @Override
  @SneakyThrows
  public String construct(
      Sep24Transaction txn, Map<String, String> request, AssetInfo asset, WebAuthJwt jwt) {
    // If there are KYC fields in the request, they will be forwarded to PUT /customer before
    // returning the token.
    forwardKycFields(request, jwt);

    // construct the token
    String token = constructToken(txn, request, asset, jwt.getHomeDomain());

    // construct the URL
    String baseUrl = sep24Config.getInteractiveUrl().getBaseUrl();
    URI uri = new URI(baseUrl);
    return new URIBuilder()
        .setScheme(uri.getScheme())
        .setHost(uri.getHost())
        .setPort(uri.getPort())
        .setPath(uri.getPath())
        .addParameter("transaction_id", txn.getTransactionId())
        // Add the JWT token
        .addParameter("token", token)
        .build()
        .toURL()
        .toString();
  }

  @SneakyThrows
  String constructToken(
      Sep24Transaction txn, Map<String, String> request, AssetInfo asset, String homeDomain) {
    ClientConfig clientConfig =
        clientsService.getClientConfigByDomainAndSep10Account(
            txn.getClientDomain(), txn.getSep10Account());

    debugF(
        "Resolving configs for token construct. Got config: {}, all configs: {}",
        clientConfig,
        clientsService);

    Sep24InteractiveUrlJwt token =
        new Sep24InteractiveUrlJwt(
            UrlConstructorHelper.getAccount(txn),
            txn.getTransactionId(),
            Instant.now().getEpochSecond() + sep24Config.getInteractiveUrl().getJwtExpiration(),
            txn.getClientDomain(),
            clientConfig != null ? clientConfig.getName() : null,
            homeDomain);

    // Add required JWT fields from request
    Map<String, String> data =
        new HashMap<>(extractRequiredJwtFieldsFromRequest(request, asset, homeDomain));
    // Add fields defined in txnFields
    addTxnFields(assetService, data, txn, sep24Config.getInteractiveUrl().getTxnFields());

    token.claim("data", data);

    debugF("Attaching data to the token: {}", data);

    return jwtService.encode(token);
  }

  void forwardKycFields(Map<String, String> request, WebAuthJwt jwt) throws AnchorException {
    if (sep24Config.getKycFieldsForwarding().isEnabled()) {
      // Get sep-9 fields from request
      Map<String, String> sep9 = extractSep9Fields(request);
      // Putting SEP-9 into JWT exposes PII
      if (!sep9.isEmpty()) {
        Gson gson = GsonUtils.getInstance();
        String gsonRequest = gson.toJson(sep9);
        PutCustomerRequest putCustomerRequest =
            gson.fromJson(gsonRequest, PutCustomerRequest.class);
        putCustomerRequest.setType(FORWARD_KYC_CUSTOMER_TYPE);
        putCustomerRequest.setAccount(jwt.getAccount());
        if (jwt.getAccountMemo() != null) {
          putCustomerRequest.setMemo(jwt.getAccountMemo());
          putCustomerRequest.setMemoType("id");
        }
        // forward kyc fields to PUT /customer
        customerIntegration.putCustomer(putCustomerRequest);
      }
    }
  }

  public static Map<String, String> extractRequiredJwtFieldsFromRequest(
      Map<String, String> request, AssetInfo asset, String homeDomain) {
    Map<String, String> fields = new HashMap<>();
    for (Map.Entry<String, String> entry : request.entrySet()) {
      if (INTERACTIVE_URL_JWT_REQUIRED_FIELDS_FROM_REQUEST.contains(entry.getKey())) {
        fields.put(entry.getKey(), entry.getValue());
      }
    }

    fields.put("asset", asset.getId());

    if (homeDomain != null) {
      fields.put(HOME_DOMAIN, homeDomain);
    }

    return fields;
  }
}
