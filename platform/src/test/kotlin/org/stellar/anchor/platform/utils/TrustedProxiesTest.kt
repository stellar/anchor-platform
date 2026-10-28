package org.stellar.anchor.platform.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.InvalidConfigException

class TrustedProxiesTest {
  @Test
  fun `an empty list trusts nothing`() {
    val proxies = TrustedProxies.parse(listOf())
    assertTrue(proxies.isEmpty)
    assertFalse(proxies.contains("10.0.0.1"))
  }

  @Test
  fun `a single address matches only itself`() {
    val proxies = TrustedProxies.parse(listOf("198.51.100.7"))
    assertTrue(proxies.contains("198.51.100.7"))
    assertFalse(proxies.contains("198.51.100.6"))
    assertFalse(proxies.contains("198.51.100.8"))
  }

  @Test
  fun `an IPv4 range includes its first and last address and excludes the neighbors`() {
    val proxies = TrustedProxies.parse(listOf("10.1.2.0/24"))
    assertTrue(proxies.contains("10.1.2.0"))
    assertTrue(proxies.contains("10.1.2.255"))
    assertFalse(proxies.contains("10.1.1.255"))
    assertFalse(proxies.contains("10.1.3.0"))
  }

  @Test
  fun `a prefix that does not end on a byte boundary is honored`() {
    val proxies = TrustedProxies.parse(listOf("172.16.0.0/12"))
    assertTrue(proxies.contains("172.16.0.0"))
    assertTrue(proxies.contains("172.31.255.255"))
    assertFalse(proxies.contains("172.15.255.255"))
    assertFalse(proxies.contains("172.32.0.0"))
  }

  @Test
  fun `a zero-length prefix matches every address of its family`() {
    val proxies = TrustedProxies.parse(listOf("0.0.0.0/0"))
    assertTrue(proxies.contains("203.0.113.9"))
    assertFalse(proxies.contains("2001:db8::1"))
  }

  @Test
  fun `a full-length prefix matches only that address`() {
    val proxies = TrustedProxies.parse(listOf("192.0.2.5/32"))
    assertTrue(proxies.contains("192.0.2.5"))
    assertFalse(proxies.contains("192.0.2.4"))
    assertFalse(proxies.contains("192.0.2.6"))
  }

  @Test
  fun `an IPv6 range includes its first and last address and excludes the neighbors`() {
    val proxies = TrustedProxies.parse(listOf("2001:db8::/32"))
    assertTrue(proxies.contains("2001:db8::"))
    assertTrue(proxies.contains("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"))
    assertFalse(proxies.contains("2001:db7:ffff:ffff:ffff:ffff:ffff:ffff"))
    assertFalse(proxies.contains("2001:db9::"))
  }

  @Test
  fun `an IPv4 address never matches an IPv6 range and the reverse`() {
    assertFalse(TrustedProxies.parse(listOf("::/0")).contains("10.0.0.1"))
    assertFalse(TrustedProxies.parse(listOf("0.0.0.0/0")).contains("::1"))
  }

  @Test
  fun `any entry of the list can match and blanks are skipped`() {
    val proxies = TrustedProxies.parse(listOf(" 10.0.0.0/8 ", "", "2001:db8::/32"))
    assertTrue(proxies.contains("10.9.9.9"))
    assertTrue(proxies.contains("2001:db8::7"))
    assertFalse(proxies.contains("11.0.0.1"))
  }

  @Test
  fun `a probed value that is not a literal never matches`() {
    val proxies = TrustedProxies.parse(listOf("0.0.0.0/0", "::/0"))
    assertFalse(proxies.contains("localhost"))
    assertFalse(proxies.contains("proxy.example.com"))
    assertFalse(proxies.contains("1.2.3.4:8080"))
    assertFalse(proxies.contains(""))
    assertFalse(proxies.contains(null))
  }

  @Test
  fun `invalid entries name the key and the entry`() {
    listOf("proxy.local", "10.0.0.0/33", "2001:db8::/129", "10.0.0.0/-1", "10.0.0.0/abc", "10.0.0")
      .forEach { entry ->
        val ex =
          assertThrows<InvalidConfigException> { TrustedProxies.parse(listOf("10.0.0.1", entry)) }
        assertTrue(ex.message!!.contains("sep_server.trusted_proxies"), ex.message)
        assertTrue(ex.message!!.contains("[$entry]"), ex.message)
      }
  }

  @Test
  fun `the longest valid prefixes are accepted`() {
    assertEquals(false, TrustedProxies.parse(listOf("10.0.0.0/32", "2001:db8::/128")).isEmpty)
  }
}
