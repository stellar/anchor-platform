package org.stellar.anchor.util;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import io.jsonwebtoken.lang.Strings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class NetUtil {
  private static final long DEFAULT_MAX_RESPONSE_SIZE = 100 * 1024;
  private static final char UTF8_BOM = '\uFEFF';

  /**
   * Fetches the content from the specified URL using an HTTP GET request.
   *
   * <p>This method expects a response body to be present in the HTTP response. If the response is
   * unsuccessful (i.e., not a 2xx status code) or if the response body is null, an IOException will
   * be thrown.
   *
   * @param url The URL to fetch content from.
   * @return The content of the response body as a string.
   * @throws IOException If the response is unsuccessful, or if the response body is null.
   */
  public static String fetch(String url) throws IOException {
    return fetch(url, DEFAULT_MAX_RESPONSE_SIZE);
  }

  public static String fetch(String url, long maxSize) throws IOException {
    Request request = OkHttpUtil.buildGetRequest(url);
    try (Response response = getCall(request).execute()) {
      // Check if response was unsuccessful (ie not status code 2xx) and throw IOException
      if (!response.isSuccessful()) {
        throw new IOException(
            "Unsuccessful response code: " + response.code() + ", message: " + response.message());
      }

      ResponseBody responseBody = response.body();
      // Since fetch expects a response body, we will throw IOException if its null
      if (responseBody == null) {
        throw new IOException(
            "Null response body. Response code: "
                + response.code()
                + ", message: "
                + response.message());
      }

      return readResponseBodyWithLimit(responseBody, maxSize);
    }
  }

  static String readResponseBodyWithLimit(ResponseBody responseBody, long maxSize)
      throws IOException {
    Charset charset = StandardCharsets.UTF_8;
    MediaType contentType = responseBody.contentType();
    if (contentType != null && contentType.charset() != null) {
      charset = contentType.charset();
    }

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long totalRead = 0;

    try (InputStream inputStream = responseBody.byteStream()) {
      int read;
      while ((read = inputStream.read(buffer)) != -1) {
        totalRead += read;
        if (totalRead > maxSize) {
          throw new IOException("Response exceeds max size of " + maxSize + " bytes");
        }
        outputStream.write(buffer, 0, read);
      }
    }

    String result = new String(outputStream.toByteArray(), charset);
    if (!result.isEmpty() && result.charAt(0) == UTF8_BOM) {
      return result.substring(1);
    }
    return result;
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  public static boolean isUrlValid(String url) {
    if (isEmpty(url)) {
      return false;
    }
    /* Try creating a valid URL */
    try {
      new URL(url).toURI();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public static boolean isServerPortValid(String serverPort, boolean hostnameLookup) {
    if (isEmpty(serverPort)) return false;
    String[] tokens = Strings.split(serverPort, ":");
    if (tokens == null) {
      return !hostnameLookup || isHostnameResolvable(serverPort);
    }
    switch (tokens.length) {
      case 2:
        String strPort = tokens[1];
        try {
          int port = Integer.parseInt(strPort);
          if (port > 65535 || port < 0) {
            return !hostnameLookup || isHostnameResolvable(serverPort);
          }
        } catch (NumberFormatException ex) {
          return false;
        }
      case 1:
        return !hostnameLookup || isHostnameResolvable(serverPort);
      case 0:
      default:
        return false;
    }
  }

  public static String getDomainFromURL(String strUri) throws MalformedURLException {
    URL uri = new URL(strUri);
    if (uri.getPort() < 0) {
      return uri.getHost();
    }
    return uri.getHost() + ":" + uri.getPort();
  }

  static boolean isHostnameResolvable(String hostname) {
    try {
      InetAddress.getAllByName(hostname);
      return true;
    } catch (Exception exc) {
      return false;
    }
  }

  static Call getCall(Request request) {
    return OkHttpUtil.buildClient().newCall(request);
  }
}
