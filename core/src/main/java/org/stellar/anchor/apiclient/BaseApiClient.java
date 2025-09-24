package org.stellar.anchor.apiclient;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.http.HttpStatus;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.util.AuthHeader;
import org.stellar.anchor.util.GsonUtils;

/** The base class for CallbackAPI and PlatformAPI clients. */
public abstract class BaseApiClient {
  static final Gson gson = GsonUtils.getInstance();
  static final OkHttpClient client =
      new OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.MINUTES)
          .readTimeout(10, TimeUnit.MINUTES)
          .writeTimeout(10, TimeUnit.MINUTES)
          .callTimeout(10, TimeUnit.MINUTES)
          .build();
  final String endpoint;

  /**
   * Creates a new BaseApiClient.
   *
   * @param endpoint the API endpoint.
   */
  protected BaseApiClient(String endpoint) {
    this.endpoint = endpoint;
  }

  String handleResponse(Response response) throws AnchorException, IOException {
    if (response.body() == null) throw new SepException("Empty response");

    String responseBody = response.body().string();
    if (response.code() == HttpStatus.SC_FORBIDDEN) {
      throw new SepNotAuthorizedException("Forbidden");
    } else if (response.code() == HttpStatus.SC_NOT_FOUND) {
      throw new SepNotFoundException("Not found");
    } else if (response.code() >= 400 || response.code() < 200) {
      throw new SepException(responseBody);
    }

    return responseBody;
  }

  Request.Builder getRequestBuilder() throws InvalidConfigException {
    Request.Builder requestBuilder =
        new Request.Builder().header("Content-Type", "application/json");

    AuthHeader<String, String> authHeader = createAuthHeader();
    return authHeader == null
        ? requestBuilder
        : requestBuilder.header(authHeader.getName(), authHeader.getValue());
  }

  abstract AuthHeader<String, String> createAuthHeader() throws InvalidConfigException;

  OkHttpClient getClient() {
    return client;
  }
}
