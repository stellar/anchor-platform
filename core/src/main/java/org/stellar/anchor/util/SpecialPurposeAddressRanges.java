package org.stellar.anchor.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * The address blocks that a {@code client_domain} must never connect to: every entry of the IANA
 * IPv4 and IPv6 Special-Purpose Address Registries that is not globally reachable (or is
 * deprecated), plus multicast, which the unicast registries do not list. Registry last updated
 * 2025-10-09.
 *
 * <p>The table is the single source of truth. A missing block is fixed by adding one line here and
 * one row to {@code SpecialPurposeAddressRangesTest}, not by a new predicate.
 *
 * <p>Decisions, so the table stays small:
 *
 * <ul>
 *   <li>{@code 192.0.0.0/24} and {@code 2001::/23} are blocked whole, including their globally
 *       reachable anycast sub-entries (PCP, TURN, AMT, AS112, ORCHIDv2): no client domain lives in
 *       an IETF-protocol block.
 *   <li>{@code 64:ff9b::/96} (NAT64) and {@code 2002::/16} (6to4) are blocked outright, whatever
 *       IPv4 address they embed.
 *   <li>{@code ::ffff:0:0:0/96} (IPv4-translated, RFC 2765) is blocked outright although the IANA
 *       registries do not list it: only a host with a SIIT translator for that prefix would route
 *       it, and then it reaches an IPv4 host chosen by the caller.
 *   <li>{@code ::ffff:0:0/96} (IPv4-mapped) is not listed: {@link InetAddress} turns every mapped
 *       address into an IPv4 address, so the IPv4 rows decide it.
 *   <li>The standalone globally reachable blocks ({@code 192.31.196.0/24}, {@code 192.52.193.0/24},
 *       {@code 192.175.48.0/24}, {@code 2620:4f:8000::/48}) are not blocked.
 * </ul>
 */
final class SpecialPurposeAddressRanges {

  private static final List<String> BLOCKS =
      List.of(
          // IPv4
          "0.0.0.0/8", // "This network"
          "10.0.0.0/8", // Private-Use
          "100.64.0.0/10", // Shared Address Space (carrier-grade NAT)
          "127.0.0.0/8", // Loopback
          "169.254.0.0/16", // Link Local
          "172.16.0.0/12", // Private-Use
          "192.0.0.0/24", // IETF Protocol Assignments
          "192.0.2.0/24", // Documentation (TEST-NET-1)
          "192.88.99.0/24", // Deprecated (6to4 relay anycast)
          "192.168.0.0/16", // Private-Use
          "198.18.0.0/15", // Benchmarking
          "198.51.100.0/24", // Documentation (TEST-NET-2)
          "203.0.113.0/24", // Documentation (TEST-NET-3)
          "224.0.0.0/4", // Multicast
          "240.0.0.0/4", // Reserved (Class E), includes the limited broadcast 255.255.255.255
          // IPv6
          "::/128", // Unspecified Address
          "::1/128", // Loopback Address
          "::ffff:0:0:0/96", // IPv4-translated (SIIT, RFC 2765); not in the IANA registries
          "64:ff9b::/96", // NAT64 well-known prefix (RFC 6052)
          "64:ff9b:1::/48", // NAT64 local-use prefix (RFC 8215)
          "100::/64", // Discard-Only Address Block
          "100:0:0:1::/64", // Dummy IPv6 Prefix
          "2001::/23", // IETF Protocol Assignments (Teredo, benchmarking, ORCHID, ...)
          "2001:db8::/32", // Documentation
          "2002::/16", // 6to4
          "3fff::/20", // Documentation
          "5f00::/16", // Segment Routing (SRv6) SIDs
          "fc00::/7", // Unique-Local
          "fe80::/10", // Link-Local Unicast
          "fec0::/10", // Deprecated site-local
          "ff00::/8" // Multicast
          );

  private record Block(byte[] prefix, int prefixLength) {
    boolean contains(byte[] address) {
      if (address.length != prefix.length) {
        return false;
      }
      int fullBytes = prefixLength / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (address[i] != prefix[i]) {
          return false;
        }
      }
      int remaining = prefixLength % 8;
      if (remaining == 0) {
        return true;
      }
      int mask = (0xFF << (8 - remaining)) & 0xFF;
      return (address[fullBytes] & mask) == (prefix[fullBytes] & mask);
    }
  }

  private static final List<Block> PARSED = parse();

  private SpecialPurposeAddressRanges() {}

  /** The table as CIDR strings, in declaration order. */
  static List<String> blocks() {
    return BLOCKS;
  }

  /** True when the address is in any block of the table. Fails closed on an unusual length. */
  static boolean isNonPublic(InetAddress address) {
    return isNonPublic(address.getAddress());
  }

  static boolean isNonPublic(byte[] address) {
    if (address.length != 4 && address.length != 16) {
      return true;
    }
    for (Block block : PARSED) {
      if (block.contains(address)) {
        return true;
      }
    }
    return false;
  }

  private static List<Block> parse() {
    List<Block> blocks = new ArrayList<>();
    for (String cidr : BLOCKS) {
      int slash = cidr.indexOf('/');
      try {
        // A literal address: no DNS lookup happens.
        byte[] prefix = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
        blocks.add(new Block(prefix, Integer.parseInt(cidr.substring(slash + 1))));
      } catch (UnknownHostException | RuntimeException e) {
        throw new ExceptionInInitializerError("Invalid special-purpose block " + cidr);
      }
    }
    return List.copyOf(blocks);
  }
}
