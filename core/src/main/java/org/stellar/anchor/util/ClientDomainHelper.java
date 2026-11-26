package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.infoF;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.sdk.KeyPair;

public class ClientDomainHelper {

  static final ThreadPoolExecutor CLIENT_DOMAIN_EXECUTOR =
      new ThreadPoolExecutor(
          4,
          8,
          60L,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          new ClientDomainThreadFactory(),
          new ThreadPoolExecutor.AbortPolicy());

  private static final int MAX_LABEL_LENGTH = 63;
  private static final int MAX_HOST_LENGTH = 253;

  private static final long BOUNDED_FETCH_TIMEOUT_MS = 2_500;
  private static final long FETCH_CALL_TIMEOUT_MS = 1_000;
  static Dns dns = BoundedDnsResolver.getDefault();
  private static final OkHttpClient FETCH_CLIENT =
      OkHttpUtil.buildClient(hostname -> dns.lookup(hostname), FETCH_CALL_TIMEOUT_MS);
  private static final OkHttpClient PUBLIC_FETCH_CLIENT =
      OkHttpUtil.buildClient(validatingDns(), FETCH_CALL_TIMEOUT_MS);

  private static class ClientDomainThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable r) {
      Thread thread = new Thread(r, "client-domain-" + counter.getAndIncrement());
      thread.setDaemon(true);
      return thread;
    }
  }

  public static String fetchSigningKeyFromClientDomainBounded(
      String clientDomain, boolean allowHttpRetry) throws SepException {
    Future<String> future = null;
    try {
      future =
          CLIENT_DOMAIN_EXECUTOR.submit(
              () -> fetchSigningKeyFromClientDomain(clientDomain, allowHttpRetry));
      return future.get(BOUNDED_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException | TimeoutException e) {
      if (future != null) {
        future.cancel(true);
      }
      infoF("client_domain resolution unavailable (bounded pool saturated or timed out)");
      throw new SepException("client_domain resolution unavailable");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof SepException) {
        throw (SepException) cause;
      }
      throw new SepException("client_domain resolution unavailable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SepException("client_domain resolution interrupted", e);
    }
  }

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
    OkHttpClient client = FETCH_CLIENT;
    if (!allowHttpRetry) {
      validateDomainNotPrivateNetwork(clientDomain);
      client = PUBLIC_FETCH_CLIENT;
    }

    String clientSigningKey = "";
    String url = "https://" + clientDomain + "/.well-known/stellar.toml";
    try {
      Sep1Helper.TomlContent toml = tryRead(url, allowHttpRetry, client);
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
      throw new SepException("Unable to read client_domain's SIGNING_KEY", e);
    } catch (InvalidConfigException e) {
      infoF("Invalid config: {}", e.getMessage());
      throw new SepException(String.format("Invalid config: %s", e.getMessage()));
    }
  }

  private static Sep1Helper.TomlContent tryRead(String url, boolean allowHttp, OkHttpClient client)
      throws IOException, InvalidConfigException {
    try {
      debugF("Fetching {}", url);
      return Sep1Helper.readToml(url, client);
    } catch (Exception e) {
      if (allowHttp) {
        try {
          var httpUrl = url.replaceFirst("^https://", "http://");
          debugF("Fetching {}", httpUrl);
          return Sep1Helper.readToml(httpUrl, client);
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
        // Escape special characters in the pattern and let '*' span only host-name characters, so
        // it can never match '/', '?', '#', '@', '\', ':' or whitespace.
        String regex = patternOrDomain.replace(".", "\\.").replace("*", "[A-Za-z0-9_.-]*");
        Pattern patternObject = Pattern.compile(regex);
        Matcher matcher = patternObject.matcher(domainName);
        if (matcher.matches() && hasValidHostLabels(domainName)) {
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
   * Checks that the host part of a domain name (without a trailing port) is at most 253 characters
   * long and made of non-empty labels of at most 63 characters that neither start nor end with a
   * hyphen, so a wildcard cannot expand to something that is not a host name.
   *
   * @param domainName The domain name, optionally followed by ":port".
   * @return true if the host part is well formed, false otherwise.
   */
  private static boolean hasValidHostLabels(String domainName) {
    String host = domainName;
    int portSeparator = host.lastIndexOf(':');
    if (portSeparator >= 0) {
      host = host.substring(0, portSeparator);
    }
    if (host.length() > MAX_HOST_LENGTH) {
      return false;
    }
    for (String label : host.split("\\.", -1)) {
      if (label.isEmpty()
          || label.length() > MAX_LABEL_LENGTH
          || label.startsWith("-")
          || label.endsWith("-")) {
        return false;
      }
    }
    return true;
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
      List<InetAddress> addresses = dns.lookup(hostname);
      for (InetAddress address : addresses) {
        if (isNonPublicAddress(address)) {
          infoF("client_domain {} resolves to non-public address {}", clientDomain, address);
          throw new SepException("client_domain resolves to a non-public address");
        }
      }
    } catch (UnknownHostException e) {
      infoF("client_domain {} could not be resolved", clientDomain);
      throw new SepException("client_domain could not be resolved");
    }
  }

  static Dns validatingDns() {
    return hostname -> {
      List<InetAddress> addresses = dns.lookup(hostname);
      for (InetAddress address : addresses) {
        if (isNonPublicAddress(address)) {
          throw new UnknownHostException(
              String.format("%s resolves to a non-public address", hostname));
        }
      }
      return addresses;
    };
  }

  /**
   * True when the address is in any block of the IANA special-purpose table (see {@link
   * SpecialPurposeAddressRanges}). An IPv4-compatible IPv6 address ({@code ::a.b.c.d}) is judged on
   * the IPv4 address it embeds; NAT64 and 6to4 addresses are refused outright by the table.
   */
  private static boolean isNonPublicAddress(InetAddress address) {
    return SpecialPurposeAddressRanges.isNonPublic(unwrapIpv4Compatible(address));
  }

  private static InetAddress unwrapIpv4Compatible(InetAddress address) {
    byte[] a = address.getAddress();
    if (a.length != 16 || !isIpv4Compatible(a)) {
      return address;
    }
    try {
      return InetAddress.getByAddress(new byte[] {a[12], a[13], a[14], a[15]});
    } catch (UnknownHostException e) {
      return address;
    }
  }

  private static boolean isIpv4Compatible(byte[] a) {
    for (int i = 0; i < 12; i++) {
      if (a[i] != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Extracts the hostname from a client domain string, using the same parser (OkHttp's {@link
   * HttpUrl}) that builds the real stellar.toml request, so the host validated here can never
   * differ from the host actually connected to. Fails closed (throws) rather than falling back to
   * the raw, unparsed string when the host cannot be extracted.
   *
   * <p>A bare (unbracketed) IPv6 literal, e.g. {@code ::1}, is not a valid URL host under RFC 3986
   * and so fails the first parse; it is retried once with brackets added, the normal way to address
   * an IPv6 literal, so the private-network guard still classifies it correctly. The retry is never
   * attempted when the raw string already contains a backslash, so it cannot interact with the
   * backslash divergence this method exists to close.
   */
  static String extractHostname(String clientDomain) throws SepException {
    String host = parseHost("https://" + clientDomain + "/.well-known/stellar.toml");
    if (host == null
        && !clientDomain.contains("\\")
        && !clientDomain.startsWith("[")
        && clientDomain.contains(":")) {
      host = parseHost("https://[" + clientDomain + "]/.well-known/stellar.toml");
    }
    if (host == null) {
      infoF("client_domain {} could not be parsed", clientDomain);
      throw new SepException("client_domain could not be resolved");
    }
    return host;
  }

  private static String parseHost(String url) {
    HttpUrl parsed = HttpUrl.parse(url);
    return parsed == null ? null : parsed.host();
  }
}
