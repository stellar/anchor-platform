package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.infoF;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.sdk.KeyPair;

public class ClientDomainHelper {

  /**
   * Fetch SIGNING_KEY from clint_domain by reading the stellar.toml content.
   *
   * @param clientDomain The client's domain. E.g. lobstr.co.
   * @param allowHttpRetry If should retry fetching toml file using http connection.
   * @return The SIGNING_KEY presented in client's TOML file.
   * @throws SepException if SIGNING_KEY not present or error happens
   */
  public static String fetchSigningKeyFromClientDomain(String clientDomain, boolean allowHttpRetry)
      throws SepException {
    String clientSigningKey = "";
    String url = "https://" + clientDomain + "/.well-known/stellar.toml";
    try {
      Sep1Helper.TomlContent toml = tryRead(url, allowHttpRetry);
      clientSigningKey = toml.getString("SIGNING_KEY");
      if (clientSigningKey == null) {
        infoF("SIGNING_KEY not present in 'client_domain' TOML.");
        throw new SepException("SIGNING_KEY not present in 'client_domain' TOML");
      }

      // client key validation
      debugF("Validating client_domain signing key: {}", clientSigningKey);
      KeyPair.fromAccountId(clientSigningKey);
      return clientSigningKey;
    } catch (IllegalArgumentException e) {
      infoF("SIGNING_KEY {} is not a valid Stellar account Id.", clientSigningKey);
      throw new SepException(
          String.format("SIGNING_KEY %s is not a valid Stellar account Id.", clientSigningKey));
    } catch (IOException e) {
      infoF("Unable to read from {}", url);
      if (allowHttpRetry) {
        throw new SepException(
            String.format("Unable to read from both %s and %s", url, url.replace("https", "http")),
            e);
      }
      throw new SepException(String.format("Unable to read from %s", url), e);
    } catch (InvalidConfigException e) {
      infoF("Invalid config: {}", e.getMessage());
      throw new SepException(String.format("Invalid config: %s", e.getMessage()));
    }
  }

  private static Sep1Helper.TomlContent tryRead(String url, boolean allowHttp)
      throws IOException, InvalidConfigException {
    try {
      debugF("Fetching {}", url);
      return Sep1Helper.readToml(url);
    } catch (Exception e) {
      if (allowHttp) {
        try {
          var httpUrl = url.replace("https", "http");
          debugF("Fetching {}", httpUrl);
          return Sep1Helper.readToml(httpUrl);
        } catch (Exception ignored) {
        }
      }
      throw e;
    }
  }

  /**
   * Checks if the given domain name matches any pattern or fixed domain in the provided list.
   *
   * @param patternsAndDomains A list containing patterns and/or fixed domain names to match
   *     against.
   * @param domainName The domain name to check for a match.
   * @return true if the domain name matches any pattern or exact domain in the list, false
   *     otherwise.
   */
  public static Boolean isDomainNameMatch(List<String> patternsAndDomains, String domainName) {
    for (String patternOrDomain : patternsAndDomains) {
      if (patternOrDomain.contains("*")) {
        // wildcard domain
        // Escape special characters in the pattern and replace '*' with '.*'
        String regex = patternOrDomain.replace(".", "\\.").replace("*", ".*");
        Pattern patternObject = Pattern.compile(regex);
        Matcher matcher = patternObject.matcher(domainName);
        if (matcher.matches()) {
          return true;
        }
      } else {
        // exact domain
        if (patternOrDomain.equals(domainName)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Retrieves the first fixed domain name from the provided list of patterns and domains.
   *
   * @param patternsAndDomains A list containing patterns and/or fixed domain names to search.
   * @return The first exact domain name found in the list, or null if no exact domain is present.
   */
  public static String getDefaultDomainName(List<String> patternsAndDomains) {
    for (String patternOrDomain : patternsAndDomains) {
      if (!patternOrDomain.contains("*")) {
        return patternOrDomain;
      }
    }
    return null;
  }
}
