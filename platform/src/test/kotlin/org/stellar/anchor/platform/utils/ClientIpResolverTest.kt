package org.stellar.anchor.platform.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpResolverTest {
  private val trusted = ClientIpResolver(TrustedProxies.parse(listOf("198.51.100.7", "10.0.0.0/8")))
  private val noProxies = ClientIpResolver(TrustedProxies.parse(listOf()))

  private fun request(peer: String, vararg headers: Pair<String, String>) =
    MockHttpServletRequest().apply {
      remoteAddr = peer
      headers.forEach { (name, value) -> addHeader(name, value) }
    }

  @Test
  fun `a peer outside the trusted list is recorded whatever the headers say`() {
    val spoofed =
      request(
        "198.51.100.99",
        "X-Forwarded-For" to "203.0.113.9",
        "X-Real-IP" to "203.0.113.8",
      )
    assertEquals("198.51.100.99", trusted.resolve(spoofed))
  }

  @Test
  fun `with no trusted proxies the report PoC records the peer`() {
    val poc = request("198.51.100.7", "X-Forwarded-For" to "203.0.113.9, 10.0.0.1")
    assertEquals("198.51.100.7", noProxies.resolve(poc))
  }

  @Test
  fun `a trusted peer yields the rightmost entry that is not a trusted proxy`() {
    val chain = request("198.51.100.7", "X-Forwarded-For" to "203.0.113.9, 10.0.0.1")
    assertEquals("203.0.113.9", trusted.resolve(chain))

    val twoProxies = request("198.51.100.7", "X-Forwarded-For" to "203.0.113.9, 10.0.0.2, 10.0.0.1")
    assertEquals("203.0.113.9", trusted.resolve(twoProxies))
  }

  @Test
  fun `an entry the caller prepended does not win`() {
    val prepended = request("198.51.100.7", "X-Forwarded-For" to "6.6.6.6, 203.0.113.9, 10.0.0.1")
    assertEquals("203.0.113.9", trusted.resolve(prepended))
  }

  @Test
  fun `when every hop is a trusted proxy the leftmost entry is recorded`() {
    val internal = request("198.51.100.7", "X-Forwarded-For" to "10.0.0.5, 10.0.0.1")
    assertEquals("10.0.0.5", trusted.resolve(internal))
  }

  @Test
  fun `a trusted peer with no usable X-Forwarded-For is recorded`() {
    assertEquals("198.51.100.7", trusted.resolve(request("198.51.100.7")))
    assertEquals(
      "198.51.100.7",
      trusted.resolve(request("198.51.100.7", "X-Forwarded-For" to "")),
    )
    assertEquals(
      "198.51.100.7",
      trusted.resolve(request("198.51.100.7", "X-Forwarded-For" to "unknown")),
    )
  }

  @Test
  fun `an entry that is not an IP literal makes the peer the answer without a name lookup`() {
    // "localhost" would resolve to 127.0.0.1 if the entry were looked up.
    listOf(
        "localhost, 10.0.0.1",
        "203.0.113.9, proxy.example.com",
        "203.0.113.9:8080, 10.0.0.1",
        "203.0.113.9,,10.0.0.1",
        "203.0.113.9, 10.0.0",
      )
      .forEach { value ->
        assertEquals(
          "198.51.100.7",
          trusted.resolve(request("198.51.100.7", "X-Forwarded-For" to value)),
          value,
        )
      }
  }

  @Test
  fun `X-Real-IP and the other legacy headers are ignored even from a trusted peer`() {
    val legacyOnly =
      request(
        "198.51.100.7",
        "X-Real-IP" to "203.0.113.8",
        "Proxy-Client-IP" to "203.0.113.7",
        "WL-Proxy-Client-IP" to "203.0.113.6",
        "HTTP_X_FORWARDED_FOR" to "203.0.113.5",
        "HTTP_CLIENT_IP" to "203.0.113.4",
        "HTTP_VIA" to "203.0.113.3",
      )
    assertEquals("198.51.100.7", trusted.resolve(legacyOnly))

    val both =
      request(
        "198.51.100.7",
        "X-Forwarded-For" to "203.0.113.9, 10.0.0.1",
        "X-Real-IP" to "203.0.113.8",
      )
    assertEquals("203.0.113.9", trusted.resolve(both))
  }

  @Test
  fun `the IPv6 loopback is recorded as 127 0 0 1`() {
    assertEquals("127.0.0.1", noProxies.resolve(request("0:0:0:0:0:0:0:1")))

    val viaProxy = request("198.51.100.7", "X-Forwarded-For" to "0:0:0:0:0:0:0:1, 10.0.0.1")
    assertEquals("127.0.0.1", trusted.resolve(viaProxy))

    assertEquals("2001:db8::5", noProxies.resolve(request("2001:db8::5")))
  }

  @Test
  fun `entries are trimmed before they are judged`() {
    val padded = request("198.51.100.7", "X-Forwarded-For" to "  203.0.113.9 ,  10.0.0.1  ")
    assertEquals("203.0.113.9", trusted.resolve(padded))
  }

  @Test
  fun `several X-Forwarded-For header lines are read as one list in the order received`() {
    val twoLines =
      request(
        "198.51.100.7",
        "X-Forwarded-For" to "6.6.6.6, 203.0.113.9",
        "X-Forwarded-For" to "10.0.0.2, 10.0.0.1",
      )
    assertEquals("203.0.113.9", trusted.resolve(twoLines))
  }

  @Test
  fun `an IPv6 entry is only compared against IPv6 ranges`() {
    // 10.0.0.0/8 must not swallow an IPv6 address; the IPv6 range must.
    val v6Client = request("198.51.100.7", "X-Forwarded-For" to "2001:db8::5, 10.0.0.1")
    assertEquals("2001:db8::5", trusted.resolve(v6Client))

    val v6Proxy = ClientIpResolver(TrustedProxies.parse(listOf("198.51.100.7", "2001:db8::/32")))
    val v6Chain = request("198.51.100.7", "X-Forwarded-For" to "203.0.113.9, 2001:db8::1")
    assertEquals("203.0.113.9", v6Proxy.resolve(v6Chain))
  }
}
