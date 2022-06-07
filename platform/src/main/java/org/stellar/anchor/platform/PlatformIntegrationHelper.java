package org.stellar.anchor.platform;

import com.google.gson.Gson;
import java.io.IOException;
import lombok.Data;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.http.HttpStatus;
import org.stellar.anchor.api.exception.*;

public class PlatformIntegrationHelper {
  public static Response call(OkHttpClient httpClient, Request request)
      throws ServiceUnavailableException {
    try {
      return httpClient.newCall(request).execute();
    } catch (IOException e) {
      throw new ServiceUnavailableException("service not available", e);
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
    ErrorResponse errorResponse;
    try {
      errorResponse = gson.fromJson(responseContent, ErrorResponse.class);
    } catch (Exception e) { // cannot read body from response
      return new ServerErrorException("internal server error", e);
    }

    // Handle 422
    String errorMessage;
    if (responseCode == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
      errorMessage =
          (errorResponse != null)
              ? errorResponse.getError()
              : HttpStatus.BAD_REQUEST.getReasonPhrase();
      return new BadRequestException(errorMessage);
    }

    errorMessage =
        (errorResponse != null)
            ? errorResponse.getError()
            : HttpStatus.valueOf(responseCode).getReasonPhrase();
    if (responseCode == HttpStatus.BAD_REQUEST.value()) {
      return new BadRequestException(errorMessage);
    } else if (responseCode == HttpStatus.NOT_FOUND.value()) {
      return new NotFoundException(errorMessage);
    }
    return new ServerErrorException("internal server error");
  }

  @Data
  static class ErrorResponse {
    String error;
  }
}
