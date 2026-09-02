package org.stellar.anchor.util;

import static org.stellar.anchor.util.Log.debugF;
import static org.stellar.anchor.util.Log.infoF;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
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

  private static final long BOUNDED_FETCH_TIMEOUT_MS = 2_500;
  private static final long FETCH_CALL_TIMEOUT_MS = 1_000;
  private static final OkHttpClient FETCH_CLIENT = OkHttpUtil.buildClient(FETCH_CALL_TIMEOUT_MS);

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
      client = OkHttpUtil.buildClient(validatingDns(), FETCH_CALL_TIMEOUT_MS);
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

  private static Dns validatingDns() {
    return hostname -> {
      List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
      for (InetAddress address : addresses) {
        if (isNonPublicAddress(address)) {
          throw new UnknownHostException(
              String.format("%s resolves to a non-public address", hostname));
        }
      }
      return addresses;
    };
  }

  private static boolean isNonPublicAddress(InetAddress address) {
    InetAddress unwrapped = unwrapEmbeddedIPv4(address);
    return unwrapped.isLoopbackAddress()
        || unwrapped.isSiteLocalAddress()
        || unwrapped.isLinkLocalAddress()
        || unwrapped.isAnyLocalAddress()
        || isCarrierGradeNat(unwrapped)
        || isIpv6UniqueLocal(unwrapped)
        || isIetfProtocolAssignment(unwrapped)
        || isBenchmarkingRange(unwrapped)
        || isThisNetwork(unwrapped)
        || isNat64WellKnown(address)
        || is6to4(address);
  }

  private static InetAddress unwrapEmbeddedIPv4(InetAddress address) {
    byte[] a = address.getAddress();
    if (a.length != 16) {
      return address;
    }

    byte[] embedded;
    if (isNat64WellKnown(address)) {
      embedded = new byte[] {a[12], a[13], a[14], a[15]};
    } else if (is6to4(address)) {
      embedded = new byte[] {a[2], a[3], a[4], a[5]};
    } else if (isIpv4Compatible(a)) {
      embedded = new byte[] {a[12], a[13], a[14], a[15]};
    } else {
      return address;
    }

    try {
      return InetAddress.getByAddress(embedded);
    } catch (UnknownHostException e) {
      return address;
    }
  }

  private static boolean isIpv4Compatible(byte[] a) {
    for (int i = 0; i < 10; i++) {
      if (a[i] != 0) {
        return false;
      }
    }
    return (a[10] & 0xFF) != 0xFF || (a[11] & 0xFF) != 0xFF;
  }

  private static boolean isThisNetwork(InetAddress address) {
    byte[] a = address.getAddress();
    return a.length == 4 && (a[0] & 0xFF) == 0;
  }

  private static boolean isNat64WellKnown(InetAddress address) {
    byte[] a = address.getAddress();
    if (a.length != 16) {
      return false;
    }
    byte[] prefix = {0, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};
    for (int i = 0; i < prefix.length; i++) {
      if (a[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean is6to4(InetAddress address) {
    byte[] a = address.getAddress();
    return a.length == 16 && (a[0] & 0xFF) == 0x20 && (a[1] & 0xFF) == 0x02;
  }

  private static boolean isCarrierGradeNat(InetAddress address) {
    byte[] a = address.getAddress();
    return a.length == 4 && (a[0] & 0xFF) == 100 && (a[1] & 0xC0) == 0x40;
  }

  private static boolean isIpv6UniqueLocal(InetAddress address) {
    byte[] a = address.getAddress();
    return a.length == 16 && (a[0] & 0xFE) == 0xFC;
  }

  private static boolean isIetfProtocolAssignment(InetAddress address) {
    byte[] a = address.getAddress();
    return a.length == 4 && (a[0] & 0xFF) == 192 && (a[1] & 0xFF) == 0 && (a[2] & 0xFF) == 0;
  }

  private static boolean isBenchmarkingRange(InetAddress address) {
    byte[] a = address.getAddress();
    return a.length == 4 && (a[0] & 0xFF) == 198 && (a[1] & 0xFE) == 18;
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
