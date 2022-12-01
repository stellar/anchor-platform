package org.stellar.anchor.util;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import io.jsonwebtoken.lang.Strings;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

public class NetUtil {
  public static String fetch(String url) throws IOException {
    Request request = OkHttpUtil.buildGetRequest(url);
    Response response = getCall(request).execute();

    if (response.body() == null) return "";
    return Objects.requireNonNull(response.body()).string();
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
