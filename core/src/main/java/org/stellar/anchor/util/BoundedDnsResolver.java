package org.stellar.anchor.util;

import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import okhttp3.Dns;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Address;
import org.xbill.DNS.Cache;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.ResolverConfig;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;
import org.xbill.DNS.lookup.LookupResult;
import org.xbill.DNS.lookup.LookupSession;

public class BoundedDnsResolver implements Dns {
  static final long DEFAULT_TIMEOUT_MS = 1_000;
  private static final int MAX_CACHE_ENTRIES = 10_000;

  private static final BoundedDnsResolver DEFAULT = new BoundedDnsResolver(DEFAULT_TIMEOUT_MS);

  private final LookupSession session;
  private final long timeoutMs;

  BoundedDnsResolver(long timeoutMs) {
    this(timeoutMs, systemResolver(timeoutMs));
  }

  BoundedDnsResolver(long timeoutMs, Resolver resolver) {
    this.timeoutMs = timeoutMs;
    ResolverConfig config = ResolverConfig.getCurrentConfig();
    Cache cache = new Cache(DClass.IN);
    cache.setMaxEntries(MAX_CACHE_ENTRIES);
    this.session =
        LookupSession.builder()
            .resolver(resolver)
            .ndots(config.ndots())
            .searchPath(config.searchPath())
            .cache(cache)
            .defaultHostsFileParser()
            .build();
  }

  public static BoundedDnsResolver getDefault() {
    return DEFAULT;
  }

  private static Resolver systemResolver(long timeoutMs) {
    Duration timeout = Duration.ofMillis(timeoutMs);
    List<Resolver> resolvers =
        ResolverConfig.getCurrentConfig().servers().stream()
            .map(
                server -> {
                  Resolver r = new SimpleResolver(server);
                  r.setTimeout(timeout);
                  return r;
                })
            .collect(Collectors.toList());
    ExtendedResolver resolver = new ExtendedResolver(resolvers);
    resolver.setTimeout(timeout);
    return resolver;
  }

  @Override
  public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    InetAddress literal = parseLiteral(hostname);
    if (literal != null) {
      return List.of(literal);
    }

    Name name;
    try {
      name = Name.fromString(hostname);
    } catch (TextParseException e) {
      throw new UnknownHostException(hostname);
    }

    CompletableFuture<List<InetAddress>> ipv4 = lookupType(hostname, name, Type.A);
    CompletableFuture<List<InetAddress>> ipv6 = lookupType(hostname, name, Type.AAAA);
    try {
      CompletableFuture.allOf(ipv4, ipv6).get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      cancel(ipv4, ipv6);
    } catch (InterruptedException e) {
      cancel(ipv4, ipv6);
      Thread.currentThread().interrupt();
      UnknownHostException ex = new UnknownHostException(hostname);
      ex.initCause(new InterruptedIOException("interrupted"));
      throw ex;
    }

    List<InetAddress> addresses = new ArrayList<>(completedOrEmpty(ipv4));
    addresses.addAll(completedOrEmpty(ipv6));
    if (addresses.isEmpty()) {
      throw new UnknownHostException(hostname);
    }
    return addresses;
  }

  private CompletableFuture<List<InetAddress>> lookupType(String hostname, Name name, int type) {
    return session
        .lookupAsync(name, type)
        .thenApply(result -> toAddresses(hostname, result))
        .exceptionally(ex -> List.of())
        .toCompletableFuture();
  }

  private static List<InetAddress> completedOrEmpty(CompletableFuture<List<InetAddress>> future) {
    if (!future.isDone() || future.isCompletedExceptionally()) {
      return List.of();
    }
    return future.join();
  }

  private static List<InetAddress> toAddresses(String hostname, LookupResult result) {
    List<InetAddress> addresses = new ArrayList<>();
    for (Record record : result.getRecords()) {
      InetAddress address = null;
      if (record instanceof ARecord) {
        address = ((ARecord) record).getAddress();
      } else if (record instanceof AAAARecord) {
        address = ((AAAARecord) record).getAddress();
      }
      if (address != null) {
        try {
          addresses.add(InetAddress.getByAddress(hostname, address.getAddress()));
        } catch (UnknownHostException e) {
          addresses.add(address);
        }
      }
    }
    return addresses;
  }

  private static void cancel(CompletableFuture<?>... futures) {
    for (CompletableFuture<?> future : futures) {
      future.cancel(true);
    }
  }

  private static InetAddress parseLiteral(String hostname) throws UnknownHostException {
    byte[] bytes = Address.toByteArray(hostname, Address.IPv4);
    if (bytes == null) {
      bytes = Address.toByteArray(hostname, Address.IPv6);
    }
    return bytes == null ? null : InetAddress.getByAddress(hostname, bytes);
  }
}
