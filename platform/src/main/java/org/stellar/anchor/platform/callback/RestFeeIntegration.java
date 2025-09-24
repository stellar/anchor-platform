package org.stellar.anchor.platform.callback;

import static okhttp3.HttpUrl.get;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.stellar.anchor.api.callback.FeeIntegration;
import org.stellar.anchor.api.callback.GetFeeRequest;
import org.stellar.anchor.api.callback.GetFeeResponse;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.ServerErrorException;
import org.stellar.anchor.auth.AuthHelper;

public class RestFeeIntegration implements FeeIntegration {
  private final String feeIntegrationEndPoint;
  private final OkHttpClient httpClient;
  private final AuthHelper authHelper;
  private final Gson gson;

  public RestFeeIntegration(
      String feeIntegrationEndPoint, OkHttpClient okHttpClient, AuthHelper authHelper, Gson gson) {
    try {
      new URI(feeIntegrationEndPoint);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("invalid 'baseUri'");
    }

    this.feeIntegrationEndPoint = feeIntegrationEndPoint;
    this.httpClient = okHttpClient;
    this.authHelper = authHelper;
    this.gson = gson;
  }

  @Override
  public GetFeeResponse getFee(GetFeeRequest request) throws AnchorException {
    HttpUrl.Builder urlBuilder = get(feeIntegrationEndPoint).newBuilder().addPathSegment("fee");
    Type type = new TypeToken<Map<String, ?>>() {}.getType();
    Map<String, String> paramsMap = gson.fromJson(gson.toJson(request), type);
    paramsMap.forEach(
        (key, value) -> {
          if (value != null) {
            urlBuilder.addQueryParameter(key, value);
          }
        });
    HttpUrl url = urlBuilder.build();

    Request httpRequest =
        PlatformIntegrationHelper.getRequestBuilder(authHelper).url(url).get().build();

    try (Response response = PlatformIntegrationHelper.call(httpClient, httpRequest)) {
      String responseContent = PlatformIntegrationHelper.getContent(response);

      if (response.code() != HttpStatus.OK.value()) {
        throw PlatformIntegrationHelper.httpError(responseContent, response.code(), gson);
      }

      GetFeeResponse feeResponse;
      try {
        feeResponse = gson.fromJson(responseContent, GetFeeResponse.class);
      } catch (Exception e) { // cannot read body from response
        throw new ServerErrorException("internal server error", e);
      }
      return feeResponse;
    }
  }
}
