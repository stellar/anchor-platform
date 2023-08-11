package org.stellar.anchor.util;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import io.jsonwebtoken.lang.Strings;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

public class NetUtil {

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

    Request request = OkHttpUtil.buildGetRequest(url);
    Response response = getCall(request).execute();

    // Check if response was unsuccessful (ie not status code 2xx) and throw IOException
    if (!response.isSuccessful()) {
      throw new IOException(
          "Unsuccessful response code: " + response.code() + ", message: " + response.message());
    }

    // Since fetch expects a response body, we will throw IOException if its null
    if (response.body() == null) {
      throw new IOException(
          "Null response body. Response code: "
              + response.code()
              + ", message: "
              + response.message());
    }

    return response.body().string();
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

  public static boolean isServerPortValid(String serverPort) {
    if (isEmpty(serverPort)) return false;
    String[] tokens = Strings.split(serverPort, ":");
    if (tokens == null) {
      return isHostnameValid(serverPort);
    }
    switch (tokens.length) {
      case 2:
        String strPort = tokens[1];
        try {
          int port = Integer.parseInt(strPort);
          if (port > 65535 || port < 0) {
            return false;
          }
        } catch (NumberFormatException ex) {
          return false;
        }
      case 1:
        return isHostnameValid(tokens[0]);
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

  static boolean isHostnameValid(String hostname) {
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
