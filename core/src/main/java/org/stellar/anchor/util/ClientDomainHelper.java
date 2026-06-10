package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.infoF;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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
    // allowHttpRetry is true for non-public networks (testnet/dev) and false for mainnet.
    // We only enforce SSRF protection on public network because test/dev environments
    // legitimately use localhost and internal addresses as client_domain.
    if (!allowHttpRetry) {
      validateDomainNotPrivateNetwork(clientDomain);
    }

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
            String.format(
                "Unable to read from both %s and %s",
                url, url.replaceFirst("^https://", "http://")),
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
          var httpUrl = url.replaceFirst("^https://", "http://");
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

  /**
   * Validates that a client domain does not resolve to a private, loopback, or link-local IP
   * address. This prevents SSRF attacks where an attacker supplies a domain that resolves to
   * internal network addresses.
   *
   * @param clientDomain The domain to validate.
   * @throws SepException if the domain resolves to a non-public IP address.
   */
  public static void validateDomainNotPrivateNetwork(String clientDomain) throws SepException {
    String hostname = extractHostname(clientDomain);

    try {
      InetAddress[] addresses = InetAddress.getAllByName(hostname);
      for (InetAddress address : addresses) {
        if (address.isLoopbackAddress()
            || address.isSiteLocalAddress()
            || address.isLinkLocalAddress()
            || address.isAnyLocalAddress()) {
          infoF("client_domain {} resolves to non-public address {}", clientDomain, address);
          throw new SepException("client_domain resolves to a non-public address");
        }
      }
    } catch (UnknownHostException e) {
      infoF("client_domain {} could not be resolved", clientDomain);
      throw new SepException("client_domain could not be resolved");
    }
  }

  /**
   * Extracts the hostname from a client domain string, correctly handling IPv6 literals (e.g.
   * [::1]:8080) and domains with ports.
   */
  static String extractHostname(String clientDomain) {
    try {
      URI uri = new URI("https://" + clientDomain);
      String host = uri.getHost();
      if (host != null) {
        if (host.startsWith("[") && host.endsWith("]")) {
          host = host.substring(1, host.length() - 1);
        }
        return host;
      }
    } catch (Exception ignored) {
      // Fall through
    }
    return clientDomain;
  }
}
