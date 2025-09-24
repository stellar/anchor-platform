package org.stellar.anchor.platform.callback;

import com.google.gson.Gson;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.stellar.anchor.api.callback.GetUniqueAddressResponse;
import org.stellar.anchor.api.callback.UniqueAddressIntegration;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.auth.AuthHelper;

public class RestUniqueAddressIntegration implements UniqueAddressIntegration {
  private final String anchorEndpoint;
  private final OkHttpClient httpClient;
  private final AuthHelper authHelper;
  private final Gson gson;

  public RestUniqueAddressIntegration(
      String anchorEndpoint, OkHttpClient httpClient, AuthHelper authHelper, Gson gson) {
    try {
      new URI(anchorEndpoint);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("invalid 'baseUri'");
    }

    this.anchorEndpoint = anchorEndpoint;
    this.httpClient = httpClient;
    this.authHelper = authHelper;
    this.gson = gson;
  }

  @Override
  public GetUniqueAddressResponse getUniqueAddress(String transactionId) throws AnchorException {
    HttpUrl url =
        HttpUrl.get(anchorEndpoint)
            .newBuilder()
            .addQueryParameter("transaction_id", transactionId)
            .addPathSegment("unique_address")
            .build();
    Request request =
        PlatformIntegrationHelper.getRequestBuilder(authHelper).url(url).get().build();
    try (Response response = PlatformIntegrationHelper.call(httpClient, request)) {
      String content = PlatformIntegrationHelper.getContent(response);

      if (!List.of(HttpStatus.OK.value(), HttpStatus.NO_CONTENT.value())
          .contains(response.code())) {
        throw PlatformIntegrationHelper.httpError(content, response.code(), gson);
      }
      return gson.fromJson(content, GetUniqueAddressResponse.class);
    }
  }
}
