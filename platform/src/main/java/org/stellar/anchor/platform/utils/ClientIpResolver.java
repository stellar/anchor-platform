package org.stellar.anchor.platform.utils;

import com.google.common.net.InetAddresses;
import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Derives the client IP address of a request. The TCP peer address is the answer unless the peer is
 * a trusted proxy; only then is {@code X-Forwarded-For} believed, and read from the right, skipping
 * trusted proxies, because the entries on the right were written by infrastructure the operator
 * controls and the ones on the left by the caller.
 */
public class ClientIpResolver {
  private static final String FORWARDED_FOR = "X-Forwarded-For";

  private final TrustedProxies trustedProxies;

  public ClientIpResolver(TrustedProxies trustedProxies) {
    this.trustedProxies = trustedProxies;
  }

  public String resolve(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    String ip = trustedProxies.contains(peer) ? resolveBehindProxy(request, peer) : peer;
    return canonical(ip);
  }

  /** IPv6 in its canonical form, any spelling of the IPv6 loopback as 127.0.0.1. */
  private static String canonical(String ip) {
    if (!InetAddresses.isInetAddress(ip)) return ip;
    InetAddress address = InetAddresses.forString(ip);
    if (address instanceof Inet6Address) {
      return address.isLoopbackAddress() ? "127.0.0.1" : InetAddresses.toAddrString(address);
    }
    return ip;
  }

  private String resolveBehindProxy(HttpServletRequest request, String peer) {
    List<String> entries = forwardedFor(request);
    if (entries.isEmpty()) return peer;

    for (int i = entries.size() - 1; i >= 0; i--) {
      String entry = entries.get(i);
      // A hostname or any other non-literal is never trusted and never resolved.
      if (!InetAddresses.isInetAddress(entry)) return peer;
      if (!trustedProxies.contains(entry)) return entry;
    }
    // Every hop is a trusted proxy: the originator is inside the trusted network.
    return entries.get(0);
  }

  private static List<String> forwardedFor(HttpServletRequest request) {
    List<String> entries = new ArrayList<>();
    for (String line : Collections.list(request.getHeaders(FORWARDED_FOR))) {
      if (line.isBlank() || "unknown".equalsIgnoreCase(line.trim())) continue;
      for (String entry : line.split(",", -1)) {
        entries.add(entry.trim());
      }
    }
    return entries;
  }
}
