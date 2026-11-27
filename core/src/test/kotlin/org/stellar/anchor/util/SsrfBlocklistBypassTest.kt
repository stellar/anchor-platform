package org.stellar.anchor.util

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.SepException

class SsrfBlocklistBypassTest {
  private fun guardBlocks(host: String): Boolean =
    try {
      ClientDomainHelper.validateDomainNotPrivateNetwork(host)
      false
    } catch (e: SepException) {
      e.message?.contains("non-public") == true
    }

  private fun mappedIpv6(vararg ipv4: Int): Inet6Address {
    val bytes =
      byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte()) +
        ipv4.map { it.toByte() }.toByteArray()
    return Inet6Address.getByAddress("mapped", bytes, 0)
  }

  private fun withDns(resolve: Map<String, InetAddress>, block: () -> Unit) {
    val original = ClientDomainHelper.dns
    ClientDomainHelper.dns =
      object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
          listOf(resolve[hostname] ?: throw UnknownHostException(hostname))
      }
    try {
      block()
    } finally {
      ClientDomainHelper.dns = original
    }
  }

  @Test
  fun `CONTROL - standard private and reserved ranges are correctly blocked`() {
    for (h in
      listOf(
        "127.0.0.1",
        "10.0.0.1",
        "172.16.0.1",
        "192.168.1.1",
        "169.254.169.254",
        "100.64.0.1",
        "::1",
        "fc00::1",
        "fd00::1",
        "::ffff:10.0.0.1",
        "::ffff:169.254.169.254",
        "::10.0.0.1"
      )) {
      assertTrue(guardBlocks(h), "$h must be blocked by the private-network guard")
    }
  }

  @Test
  fun `BYPASS - NAT64 (64_ff9b__96) addresses embedding internal IPv4 are now blocked`() {
    for (h in listOf("64:ff9b::a9fe:a9fe", "64:ff9b::a00:1", "64:ff9b::7f00:1")) {
      assertTrue(
        guardBlocks(h),
        "$h reaches an internal IPv4 through a NAT64 gateway and must be blocked"
      )
    }
  }

  @Test
  fun `BYPASS - 6to4 (2002__16) addresses embedding internal IPv4 are now blocked`() {
    for (h in listOf("2002:a00:1::", "2002:7f00:1::")) {
      assertTrue(
        guardBlocks(h),
        "$h reaches an internal IPv4 through a 6to4 relay and must be blocked"
      )
    }
  }

  @Test
  fun `additional - NAT64 local-use prefix (64_ff9b_1__48, RFC 8215) is blocked`() {
    assertTrue(
      guardBlocks("64:ff9b:1::a00:1"),
      "64:ff9b:1::/48 is the RFC 8215 local-use NAT64 prefix and must be blocked outright"
    )
  }

  @Test
  fun `additional - 0_0_0_0_8 range is blocked`() {
    assertTrue(guardBlocks("0.1.2.3"), "0.1.2.3 is in 0.0.0.0/8 and must be blocked")
  }

  @Test
  fun `BYPASS - a DNS-returned IPv4-mapped IPv6 embedding an internal IPv4 is blocked`() {
    withDns(
      mapOf(
        "mapped-metadata.test" to mappedIpv6(169, 254, 169, 254),
        "mapped-rfc1918.test" to mappedIpv6(10, 99, 99, 99),
      )
    ) {
      assertTrue(
        guardBlocks("mapped-metadata.test"),
        "::ffff:169.254.169.254 from DNS reaches the link-local metadata IP and must be blocked",
      )
      assertTrue(
        guardBlocks("mapped-rfc1918.test"),
        "::ffff:10.99.99.99 from DNS reaches an RFC1918 IP and must be blocked",
      )
    }
  }

  @Test
  fun `additional - a DNS-returned IPv4-mapped IPv6 of a public IPv4 is not blocked`() {
    withDns(mapOf("mapped-public.test" to mappedIpv6(8, 8, 8, 8))) {
      assertFalse(
        guardBlocks("mapped-public.test"),
        "::ffff:8.8.8.8 maps to a public IPv4 and must not be blocked",
      )
    }
  }

  @Test
  fun `additional - ordinary IPv6 addresses outside __96 are not misclassified as IPv4-compatible`() {
    assertFalse(
      guardBlocks("::1:a00:1"),
      "::1:a00:1 is an ordinary global IPv6 address, not an IPv4-compatible literal, even though" +
        " its last 32 bits look like a private IPv4 address; it must not be blocked"
    )
  }
}
