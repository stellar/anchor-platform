package org.stellar.anchor.platform.callback;

import static okhttp3.HttpUrl.get;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import okhttp3.*;
import okhttp3.HttpUrl.Builder;
import org.springframework.http.HttpStatus;
import org.stellar.anchor.api.callback.*;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.ServerErrorException;
import org.stellar.anchor.auth.AuthHelper;
import org.stellar.anchor.util.Log;

public class RestCustomerIntegration implements CustomerIntegration {
  private final String anchorEndpoint;
  private final OkHttpClient httpClient;
  private final AuthHelper authHelper;
  private final Gson gson;

  public RestCustomerIntegration(
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
  public GetCustomerResponse getCustomer(GetCustomerRequest customerRequest)
      throws AnchorException {
    // prepare request
    Builder urlBuilder = getCustomerUrlBuilder();
    Type type = new TypeToken<Map<String, ?>>() {}.getType();
    Map<String, String> paramsMap = gson.fromJson(gson.toJson(customerRequest), type);
    paramsMap.forEach(
        (key, value) -> {
          if (value != null) {
            urlBuilder.addQueryParameter(key, value);
          }
        });

    HttpUrl url = urlBuilder.build();

    // Make request
    try (Response response =
        PlatformIntegrationHelper.call(
            httpClient,
            PlatformIntegrationHelper.getRequestBuilder(authHelper).url(url).get().build())) {
      String responseContent = PlatformIntegrationHelper.getContent(response);

      if (response.code() != HttpStatus.OK.value()) {
        throw PlatformIntegrationHelper.httpError(responseContent, response.code(), gson);
      }

      GetCustomerResponse getCustomerResponse;
      try {
        getCustomerResponse = gson.fromJson(responseContent, GetCustomerResponse.class);
      } catch (Exception e) { // cannot read body from response
        throw new ServerErrorException("internal server error", e);
      }

      if (getCustomerResponse.getStatus() == null) {
        Log.error("GET {callbackAPI}/customer response is missing the status field");
        throw new ServerErrorException(
            "internal server error: result from Anchor backend is invalid");
      }
      return getCustomerResponse;
    }
  }

  @Override
  public PutCustomerResponse putCustomer(PutCustomerRequest putCustomerRequest)
      throws AnchorException {
    Request callbackRequest = createCallbackRequest(putCustomerRequest);

    // Call anchor
    try (Response response = PlatformIntegrationHelper.call(httpClient, callbackRequest)) {
      String responseContent = PlatformIntegrationHelper.getContent(response);

      if (!List.of(HttpStatus.OK.value(), HttpStatus.CREATED.value(), HttpStatus.ACCEPTED.value())
          .contains(response.code())) {
        throw PlatformIntegrationHelper.httpError(responseContent, response.code(), gson);
      }

      try {
        return gson.fromJson(responseContent, PutCustomerResponse.class);
      } catch (Exception e) {
        throw new ServerErrorException("internal server error", e);
      }
    }
  }

  private Request createCallbackRequest(PutCustomerRequest putCustomerRequest)
      throws AnchorException {
    boolean hasBinaryFields = false;

    /*
     * Check if the request has binary fields. If it does, we need to use multipart/form-data
     * instead of application/json.
     */
    for (Field field : putCustomerRequest.getClass().getDeclaredFields()) {
      if (!Modifier.isPrivate(field.getModifiers())) {
        field.setAccessible(true);
        try {
          Object value = field.get(putCustomerRequest);
          if (value instanceof byte[]) {
            hasBinaryFields = true;
            break;
          }
        } catch (IllegalAccessException e) {
          throw new BadRequestException("invalid request body");
        }
      }
    }

    if (hasBinaryFields) {
      MultipartBody requestBody = convertToMultipart(putCustomerRequest);
      return PlatformIntegrationHelper.getRequestBuilder(authHelper)
          .url(getCustomerUrlBuilder().build())
          .put(requestBody)
          .build();
    } else {
      RequestBody requestBody =
          RequestBody.create(gson.toJson(putCustomerRequest), MediaType.get("application/json"));
      return PlatformIntegrationHelper.getRequestBuilder(authHelper)
          .url(getCustomerUrlBuilder().build())
          .put(requestBody)
          .build();
    }
  }

  private MultipartBody convertToMultipart(PutCustomerRequest putCustomerRequest)
      throws AnchorException {
    MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);

    for (Field field : putCustomerRequest.getClass().getDeclaredFields()) {
      if (!Modifier.isPrivate(field.getModifiers())) {
        field.setAccessible(true);
        try {
          Object value = field.get(putCustomerRequest);
          if (value != null) {
            SerializedName serializedName = field.getAnnotation(SerializedName.class);
            String name = (serializedName != null) ? serializedName.value() : field.getName();

            if (value instanceof byte[]) {
              byte[] bytes = (byte[]) value;
              RequestBody fileBody =
                  RequestBody.create(bytes, MediaType.parse("application/octet-stream"));
              builder.addFormDataPart(name, field.getName(), fileBody);
            } else {
              builder.addFormDataPart(name, value.toString());
            }
          }
        } catch (IllegalAccessException e) {
          throw new BadRequestException("invalid request body");
        }
      }
    }

    return builder.build();
  }

  @Override
  public void deleteCustomer(String id) throws AnchorException {
    HttpUrl url = getCustomerUrlBuilder().addPathSegment(id).build();
    Request callbackRequest =
        PlatformIntegrationHelper.getRequestBuilder(authHelper).url(url).delete().build();

    // Call anchor
    try (Response response = PlatformIntegrationHelper.call(httpClient, callbackRequest)) {
      String responseContent = PlatformIntegrationHelper.getContent(response);

      if (!List.of(HttpStatus.OK.value(), HttpStatus.NO_CONTENT.value())
          .contains(response.code())) {
        throw PlatformIntegrationHelper.httpError(responseContent, response.code(), gson);
      }
    }
  }

  Builder getCustomerUrlBuilder() {
    return get(anchorEndpoint).newBuilder().addPathSegment("customer");
  }
}
