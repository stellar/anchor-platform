package org.stellar.anchor.util

import java.math.BigInteger
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Walks the IANA IPv4 and IPv6 Special-Purpose Address Registries (last updated 2025-10-09).
 *
 * The expected data below is the test's own copy of the registry, written from the two IANA pages,
 * not read from the implementation: a block missing from [SpecialPurposeAddressRanges] must fail
 * here. Each entry is a block that is not globally reachable (or deprecated), plus multicast, which
 * the unicast registries do not list.
 */
class SpecialPurposeAddressRangesTest {

  private data class Range(val cidr: String, val name: String) {
    val bytes: ByteArray
    val prefixLength: Int = cidr.substringAfter('/').toInt()
    val first: BigInteger
    val last: BigInteger

    init {
      bytes = InetAddress.getByName(cidr.substringBefore('/')).address
      val bits = bytes.size * 8
      val base = BigInteger(1, bytes)
      val hostBits = bits - prefixLength
      first = base.shiftRight(hostBits).shiftLeft(hostBits)
      last = first.add(BigInteger.ONE.shiftLeft(hostBits)).subtract(BigInteger.ONE)
    }

    fun contains(address: InetAddress): Boolean {
      if (address.address.size != bytes.size) return false
      val v = BigInteger(1, address.address)
      return v >= first && v <= last
    }
  }

  // IANA IPv4 Special-Purpose Address Space
  private val ipv4 =
    listOf(
      Range("0.0.0.0/8", "This network"),
      Range("10.0.0.0/8", "Private-Use"),
      Range("100.64.0.0/10", "Shared Address Space"),
      Range("127.0.0.0/8", "Loopback"),
      Range("169.254.0.0/16", "Link Local"),
      Range("172.16.0.0/12", "Private-Use"),
      Range("192.0.0.0/24", "IETF Protocol Assignments"),
      Range("192.0.2.0/24", "Documentation (TEST-NET-1)"),
      Range("192.88.99.0/24", "Deprecated (6to4 Relay Anycast)"),
      Range("192.168.0.0/16", "Private-Use"),
      Range("198.18.0.0/15", "Benchmarking"),
      Range("198.51.100.0/24", "Documentation (TEST-NET-2)"),
      Range("203.0.113.0/24", "Documentation (TEST-NET-3)"),
      Range("224.0.0.0/4", "Multicast"),
      Range("240.0.0.0/4", "Reserved (Class E) and Limited Broadcast")
    )

  // IANA IPv6 Special-Purpose Address Space. ::ffff:0:0/96 (IPv4-mapped) is covered separately:
  // Java turns every mapped address into an Inet4Address before any lookup.
  private val ipv6 =
    listOf(
      Range("::/128", "Unspecified Address"),
      Range("::1/128", "Loopback Address"),
      Range("64:ff9b::/96", "IPv4-IPv6 Translat. (RFC 6052)"),
      Range("64:ff9b:1::/48", "IPv4-IPv6 Translat. (RFC 8215)"),
      Range("100::/64", "Discard-Only Address Block"),
      Range("100:0:0:1::/64", "Dummy IPv6 Prefix"),
      Range("2001::/23", "IETF Protocol Assignments"),
      Range("2001:db8::/32", "Documentation"),
      Range("2002::/16", "6to4"),
      Range("3fff::/20", "Documentation"),
      Range("5f00::/16", "Segment Routing (SRv6) SIDs"),
      Range("fc00::/7", "Unique-Local"),
      Range("fe80::/10", "Link-Local Unicast"),
      Range("fec0::/10", "Deprecated site-local"),
      Range("ff00::/8", "Multicast")
    )

  private val all = ipv4 + ipv6

  private fun blocked(literal: String): Boolean =
    SpecialPurposeAddressRanges.isNonPublic(InetAddress.getByName(literal))

  private fun addressOf(value: BigInteger, length: Int): InetAddress {
    val raw = value.toByteArray()
    val out = ByteArray(length)
    // toByteArray may carry a leading sign byte or omit leading zeros
    val copy = minOf(raw.size, length)
    System.arraycopy(raw, raw.size - copy, out, length - copy, copy)
    return InetAddress.getByAddress(out)
  }

  @Test
  fun `every registry block is present in the implementation table`() {
    val implemented = SpecialPurposeAddressRanges.blocks().toSet()
    for (range in all) {
      assertTrue(
        implemented.contains(range.cidr),
        "${range.cidr} (${range.name}) is not in the implementation table"
      )
    }
    assertEquals(all.size, SpecialPurposeAddressRanges.blocks().size)
  }

  @Test
  fun `first and last address of every registry block are refused`() {
    for (range in all) {
      val length = range.bytes.size
      for (edge in listOf(range.first, range.last)) {
        val address = addressOf(edge, length)
        assertTrue(
          SpecialPurposeAddressRanges.isNonPublic(address),
          "${address.hostAddress} is in ${range.cidr} (${range.name}) and must be refused"
        )
      }
    }
  }

  @Test
  fun `address next to every registry block is accepted unless another block holds it`() {
    for (range in all) {
      val length = range.bytes.size
      val max = BigInteger.ONE.shiftLeft(length * 8).subtract(BigInteger.ONE)
      val neighbors = buildList {
        if (range.first > BigInteger.ZERO) add(range.first.subtract(BigInteger.ONE))
        if (range.last < max) add(range.last.add(BigInteger.ONE))
      }
      for (n in neighbors) {
        val address = addressOf(n, length)
        if (all.any { it.contains(address) }) continue
        assertFalse(
          SpecialPurposeAddressRanges.isNonPublic(address),
          "${address.hostAddress} is next to ${range.cidr} (${range.name}) but is public and must be accepted"
        )
      }
    }
  }

  @Test
  fun `Class E and the limited broadcast address are refused`() {
    for (h in listOf("240.0.0.0", "240.0.0.5", "247.1.2.3", "255.255.255.254", "255.255.255.255")) {
      assertTrue(blocked(h), "$h is in 240.0.0.0/4 and must be refused")
    }
  }

  @Test
  fun `multicast boundary`() {
    assertTrue(blocked("224.0.0.1"))
    assertTrue(blocked("239.255.255.255"))
    assertFalse(blocked("223.255.255.255"), "223.255.255.255 is just below multicast")
  }

  @Test
  fun `IPv4-mapped IPv6 literals are judged as the embedded IPv4 address`() {
    assertTrue(blocked("::ffff:240.0.0.5"))
    assertTrue(blocked("::ffff:10.0.0.1"))
    assertFalse(blocked("::ffff:8.8.8.8"))
  }

  @Test
  fun `standalone globally reachable registry blocks are accepted`() {
    for (h in listOf("192.31.196.1", "192.52.193.1", "192.175.48.1", "2620:4f:8000::1")) {
      assertFalse(blocked(h), "$h is in a block the registry marks globally reachable")
    }
  }

  @Test
  fun `ordinary public addresses are accepted`() {
    for (h in listOf("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111", "2001:4860:4860::8888")) {
      assertFalse(blocked(h), "$h is public and must be accepted")
    }
  }

  @Test
  fun `an address that is neither 4 nor 16 bytes long is refused`() {
    assertTrue(SpecialPurposeAddressRanges.isNonPublic(ByteArray(5)))
    assertTrue(SpecialPurposeAddressRanges.isNonPublic(ByteArray(0)))
  }
}
