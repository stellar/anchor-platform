package org.stellar.anchor.platform.custody.fireblocks;

import static org.stellar.anchor.auth.AuthHelper.jwtsBuilder;
import static org.stellar.anchor.util.OkHttpUtil.TYPE_JSON;

import io.jsonwebtoken.SignatureAlgorithm;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.validation.constraints.NotNull;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.stellar.anchor.api.exception.FireblocksException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.platform.config.FireblocksConfig;

/**
 * API client, that is responsible for communication with Fireblocks. It generates and adds JWT
 * token to the request and validates the response status code
 */
public class FireblocksApiClient {

  private static final String API_KEY_HEADER_NAME = "X-API-Key";
  private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
  private static final String TOKEN_PREFIX = "Bearer ";
  private static final int TOKEN_EXPIRATION_SECONDS = 55;
  private static final String SHA256_ALGORITHM = "SHA-256";

  private final OkHttpClient client;
  private final String baseUrl;
  private final String apiKey;

  private final PrivateKey privateKey;

  public FireblocksApiClient(OkHttpClient httpClient, FireblocksConfig fireblocksConfig)
      throws InvalidConfigException {
    this.client = httpClient;
    this.baseUrl = fireblocksConfig.getBaseUrl();
    this.apiKey = fireblocksConfig.getSecretConfig().getFireblocksApiKey();
    this.privateKey = fireblocksConfig.getFireblocksPrivateKey();
  }

  public String get(String path) throws FireblocksException {
    return get(path, Map.of());
  }

  public String get(String path, Map<String, String> queryParams) throws FireblocksException {
    path = buildPath(path, queryParams);
    return doRequest(
        new Request.Builder()
            .url(baseUrl + path)
            .addHeader(API_KEY_HEADER_NAME, apiKey)
            .addHeader(AUTHORIZATION_HEADER_NAME, TOKEN_PREFIX + signJwt(path, StringUtils.EMPTY))
            .build());
  }

  public String post(String path, String data) throws FireblocksException {
    return doRequest(
        new Request.Builder()
            .url(baseUrl + path)
            .addHeader(API_KEY_HEADER_NAME, apiKey)
            .addHeader(AUTHORIZATION_HEADER_NAME, TOKEN_PREFIX + signJwt(path, data))
            .post(RequestBody.create(data.getBytes(), TYPE_JSON))
            .build());
  }

  private String doRequest(Request request) throws FireblocksException {
    try (Response response = client.newCall(request).execute()) {
      ResponseBody responseBody = response.body();
      String responseBodyJson = null;

      if (responseBody != null) {
        responseBodyJson = responseBody.string();
      }

      if (HttpStatus.valueOf(response.code()).is2xxSuccessful()) {
        return responseBodyJson;
      } else {
        throw new FireblocksException(responseBodyJson, response.code());
      }
    } catch (IOException e) {
      throw new FireblocksException(e);
    }
  }

  private String signJwt(String path, String dataJSONString) {
    String bodyHash;

    try {
      MessageDigest digest = MessageDigest.getInstance(SHA256_ALGORITHM);
      digest.update(dataJSONString.getBytes());
      BigInteger number = new BigInteger(1, digest.digest());
      StringBuilder hexString = new StringBuilder(number.toString(16));

      while (hexString.length() < 64) {
        hexString.insert(0, '0');
      }

      bodyHash = hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("Unable to generate body hash for Fireblocks JWT token", e);
    }

    Instant now = Instant.now();
    Instant expirationTime = now.plusSeconds(TOKEN_EXPIRATION_SECONDS);

    return jwtsBuilder()
        .setSubject(apiKey)
        .setIssuedAt(Date.from(now))
        .setExpiration(Date.from(expirationTime))
        .claim("nonce", UUID.randomUUID().toString())
        .claim("uri", path)
        .claim("bodyHash", bodyHash)
        .signWith(SignatureAlgorithm.RS256, privateKey)
        .compact();
  }

  private String buildPath(@NotNull String path, Map<String, String> params) {
    if (params.isEmpty()) {
      return path;
    }

    UriBuilder builder = UriComponentsBuilder.newInstance();
    builder.path(path);

    for (Map.Entry<String, String> param : params.entrySet()) {
      builder.queryParam(param.getKey(), param.getValue());
    }

    return builder.build().toString();
  }
}
