package org.stellar.anchor.util;

import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

@SuppressWarnings("unused")
public class OkHttpUtil {
  public static final String APPLICATION_JSON_CHARSET_UTF_8 = "application/json; charset=utf-8";
  public static final MediaType TYPE_JSON = MediaType.parse(APPLICATION_JSON_CHARSET_UTF_8);
  private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;
  private static final long DEFAULT_READ_TIMEOUT_SECONDS = 10;
  private static final long DEFAULT_CALL_TIMEOUT_SECONDS = 15;

  public static OkHttpClient buildClient() {
    return new OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();
  }

  public static Request buildJsonPostRequest(String url, String requestBody) {
    return new Request.Builder()
        .url(url)
        .header("Content-Type", APPLICATION_JSON_CHARSET_UTF_8)
        .post(buildJsonRequestBody(requestBody))
        .build();
  }

  public static Request buildJsonPutRequest(String url, String requestBody) {
    return new Request.Builder()
        .url(url)
        .header("Content-Type", APPLICATION_JSON_CHARSET_UTF_8)
        .put(buildJsonRequestBody(requestBody))
        .build();
  }

  public static Request buildGetRequest(String url) {
    return new Request.Builder().url(url).get().build();
  }

  public static RequestBody buildJsonRequestBody(String payload) {
    return RequestBody.create(payload, TYPE_JSON);
  }
}
