package org.stellar.anchor.platform.utils;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.stellar.anchor.api.exception.InvalidConfigException;

/**
 * The proxies whose forwarding headers are believed, as declared in {@code
 * sep_server.trusted_proxies}. An entry is an IPv4/IPv6 address or a CIDR range. Entries and probed
 * addresses are matched as literals only: a hostname is never resolved.
 */
public class TrustedProxies {
  private final List<Range> ranges;

  private TrustedProxies(List<Range> ranges) {
    this.ranges = ranges;
  }

  public static TrustedProxies parse(List<String> entries) throws InvalidConfigException {
    List<Range> ranges = new ArrayList<>();
    for (String entry : entries) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) continue;
      ranges.add(parseRange(trimmed));
    }
    return new TrustedProxies(ranges);
  }

  public boolean isEmpty() {
    return ranges.isEmpty();
  }

  /**
   * @return true if the literal IP address equals or lies inside any entry.
   */
  public boolean contains(String ip) {
    if (ip == null || !InetAddresses.isInetAddress(ip)) return false;
    byte[] address = InetAddresses.forString(ip).getAddress();
    return ranges.stream().anyMatch(range -> range.contains(address));
  }

  private static Range parseRange(String entry) throws InvalidConfigException {
    String addressPart = entry;
    Integer prefixLength = null;
    int slash = entry.indexOf('/');
    if (slash >= 0) {
      addressPart = entry.substring(0, slash);
      try {
        prefixLength = Integer.parseInt(entry.substring(slash + 1));
      } catch (NumberFormatException e) {
        throw invalid(entry);
      }
    }
    if (!InetAddresses.isInetAddress(addressPart)) throw invalid(entry);
    InetAddress address = InetAddresses.forString(addressPart);
    int bits = address.getAddress().length * 8;
    if (prefixLength == null) prefixLength = bits;
    if (prefixLength < 0 || prefixLength > bits) throw invalid(entry);
    return new Range(address.getAddress(), prefixLength);
  }

  private static InvalidConfigException invalid(String entry) {
    return new InvalidConfigException(
        String.format(
            "Invalid entry [%s] in sep_server.trusted_proxies: expected an IPv4/IPv6 address or CIDR range",
            entry));
  }

  private static class Range {
    private final byte[] network;
    private final int prefixLength;

    Range(byte[] network, int prefixLength) {
      this.network = network;
      this.prefixLength = prefixLength;
    }

    boolean contains(byte[] address) {
      // An IPv4 address is only compared against IPv4 ranges, an IPv6 one against IPv6 ranges.
      if (address.length != network.length) return false;
      int fullBytes = prefixLength / 8;
      if (!Arrays.equals(address, 0, fullBytes, network, 0, fullBytes)) return false;
      int remainingBits = prefixLength % 8;
      if (remainingBits == 0) return true;
      int mask = 0xFF << (8 - remainingBits);
      return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }
  }
}
