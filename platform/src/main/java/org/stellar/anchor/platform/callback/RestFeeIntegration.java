package org.stellar.anchor.platform.callback;

import static okhttp3.HttpUrl.get;
import static org.stellar.anchor.platform.PlatformIntegrationHelper.*;

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
import shadow.com.google.common.reflect.TypeToken;

public class RestFeeIntegration implements FeeIntegration {
  private final String feeIntegrationEndPoint;
  private final OkHttpClient httpClient;
  private final Gson gson;

  public RestFeeIntegration(String feeIntegrationEndPoint, OkHttpClient okHttpClient, Gson gson) {
    try {
      new URI(feeIntegrationEndPoint);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("invalid 'baseUri'");
    }

    this.feeIntegrationEndPoint = feeIntegrationEndPoint;
    this.httpClient = okHttpClient;
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
        new Request.Builder().url(url).header("Content-Type", "application/json").get().build();
    Response response = call(httpClient, httpRequest);
    String responseContent = getContent(response);

    if (response.code() != HttpStatus.OK.value()) {
      throw httpError(responseContent, response.code(), gson);
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
