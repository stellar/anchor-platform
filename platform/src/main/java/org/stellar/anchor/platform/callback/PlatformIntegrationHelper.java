package org.stellar.anchor.platform.callback;

import com.google.gson.Gson;
import java.io.IOException;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.http.HttpStatus;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.auth.AuthHelper;
import org.stellar.anchor.util.AuthHeader;
import org.stellar.anchor.util.Log;

public class PlatformIntegrationHelper {
  public static Request.Builder getRequestBuilder(AuthHelper authHelper)
      throws InvalidConfigException {
    Request.Builder requestBuilder =
        new Request.Builder().header("Content-Type", "application/json");

    AuthHeader<String, String> authHeader =
        (authHelper == null) ? null : authHelper.createCallbackAuthHeader();
    return authHeader == null
        ? requestBuilder
        : requestBuilder.header(authHeader.getName(), authHeader.getValue());
  }

  public static Response call(OkHttpClient httpClient, Request request)
      throws ServerErrorException {
    try {
      return httpClient.newCall(request).execute();
    } catch (IOException e) {
      throw new ServerErrorException("service not available", e);
    }
  }

  public static String getContent(Response response) throws ServerErrorException {
    try {
      ResponseBody responseBody = response.body();
      if (responseBody == null) {
        throw new ServerErrorException("unable to fetch response body");
      }
      return responseBody.string();
    } catch (Exception e) {
      throw new ServerErrorException("internal server error", e);
    }
  }

  public static AnchorException httpError(String responseContent, int responseCode, Gson gson) {
    Log.infoF(
        "Error returned from the Anchor Backend.\nresponseCode={}\nContent={}",
        responseCode,
        responseContent);
    ErrorResponse errorResponse = null;
    try {
      errorResponse = gson.fromJson(responseContent, ErrorResponse.class);
    } catch (Exception e) {
      // cannot read body from response
      Log.warn("Failed to parse responseContent to an ErrorResponse object.");
    }

    String errorMessage =
        (errorResponse != null)
            ? errorResponse.getError()
            : (responseCode == 422)
                ? HttpStatus.BAD_REQUEST.getReasonPhrase()
                : HttpStatus.valueOf(responseCode).getReasonPhrase();

    switch (HttpStatus.valueOf(responseCode)) {
      case UNPROCESSABLE_ENTITY: // 422
      case BAD_REQUEST: // 400
        return new BadRequestException(errorMessage);
      case NOT_FOUND: // 404
        return new NotFoundException(errorMessage);
      default:
        Log.errorF("Unsupported status code {}.", responseCode);
        return new ServerErrorException("internal server error");
    }
  }

  @Data
  static class ErrorResponse {
    String error;
  }
}
