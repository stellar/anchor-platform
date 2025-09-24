package org.stellar.anchor.apiclient;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import com.google.gson.Gson;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.stellar.anchor.api.callback.SendEventRequest;
import org.stellar.anchor.api.callback.SendEventResponse;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.auth.AuthHelper;
import org.stellar.anchor.util.AuthHeader;
import org.stellar.anchor.util.GsonUtils;
import org.stellar.anchor.util.OkHttpUtil;

/** The client for the CallbackAPI endpoints. */
public class CallbackApiClient extends BaseApiClient {
  static final Gson gson = GsonUtils.getInstance();
  final HttpUrl url;
  private final AuthHelper authHelper;

  /**
   * Creates a new CallbackApiClient.
   *
   * @param authHelper the AuthHelper to use for authentication.
   * @param endpoint the API endpoint.
   * @throws InvalidConfigException if the endpoint is invalid.
   */
  public CallbackApiClient(AuthHelper authHelper, String endpoint) throws InvalidConfigException {
    super(endpoint);
    this.authHelper = authHelper;
    HttpUrl endpointUrl = HttpUrl.parse(endpoint);
    if (endpointUrl == null)
      throw new InvalidConfigException(
          String.format("Invalid endpoint: %s of the client.", endpoint));
    this.url =
        new HttpUrl.Builder()
            .scheme(endpointUrl.scheme())
            .host(endpointUrl.host())
            .port(endpointUrl.port())
            .addPathSegment("event")
            .build();
  }

  /**
   * Sends an event to the /event Callback API endpoint.
   *
   * @param sendEventRequest the SendEventRequest to send.
   * @return the SendEventResponse.
   * @throws AnchorException if the response is not successful.
   * @throws IOException if the request fails due to IO errors.
   */
  public SendEventResponse sendEvent(SendEventRequest sendEventRequest)
      throws AnchorException, IOException {
    RequestBody requestBody = OkHttpUtil.buildJsonRequestBody(gson.toJson(sendEventRequest));
    Request request = getRequestBuilder().url(url).post(requestBody).build();
    Response response = getClient().newCall(request).execute();
    String responseText = handleResponse(response);

    return new SendEventResponse(response.code(), isEmpty(responseText) ? "" : responseText);
  }

  @Override
  AuthHeader<String, String> createAuthHeader() throws InvalidConfigException {
    return authHelper.createCallbackAuthHeader();
  }
}
