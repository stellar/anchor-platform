package org.stellar.anchor.util

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.SepException

class ClientDomainHelperTest {

  @Test
  fun `test loopback address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("127.0.0.1")
    }
  }

  @Test
  fun `test localhost is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("localhost")
    }
  }

  @Test
  fun `test link-local address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("169.254.169.254")
    }
  }

  @Test
  fun `test private 10-x address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("10.0.0.1")
    }
  }

  @Test
  fun `test private 192-168 address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("192.168.1.1")
    }
  }

  @Test
  fun `test private 172-16 address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("172.16.0.1")
    }
  }

  @Test
  fun `test domain with port strips port before resolving`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("127.0.0.1:8080")
    }
  }

  @Test
  fun `test unresolvable domain is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork(
        "this-domain-does-not-exist-xyz123.invalid"
      )
    }
  }

  @Test
  fun `test public address is accepted`() {
    assertDoesNotThrow { ClientDomainHelper.validateDomainNotPrivateNetwork("8.8.8.8") }
  }

  @Test
  fun `test public IPv6 address is accepted`() {
    assertDoesNotThrow {
      ClientDomainHelper.validateDomainNotPrivateNetwork("2001:4860:4860::8888")
    }
  }

  @Test
  fun `test carrier-grade NAT address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("100.64.0.1")
    }
  }

  @Test
  fun `test carrier-grade NAT upper boundary is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("100.127.255.255")
    }
  }

  @Test
  fun `test address just below carrier-grade NAT range is accepted`() {
    assertDoesNotThrow { ClientDomainHelper.validateDomainNotPrivateNetwork("100.63.255.255") }
  }

  @Test
  fun `test IPv6 unique local address fc00 is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("fc00::1")
    }
  }

  @Test
  fun `test IPv6 unique local address fd00 is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("fd00::1")
    }
  }

  @Test
  fun `test IETF protocol assignment address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("192.0.0.1")
    }
  }

  @Test
  fun `test benchmarking range address is rejected`() {
    assertThrows(SepException::class.java) {
      ClientDomainHelper.validateDomainNotPrivateNetwork("198.18.0.1")
    }
  }

  @Test
  fun `test fetchSigningKeyFromClientDomain on mainnet is blocked from reaching a loopback server`() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/.well-known/stellar.toml") { exchange ->
      val body = "SIGNING_KEY=\"GABC\"".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomain(
          "127.0.0.1:${server.address.port}",
          false,
        )
      }
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `test fetchSigningKeyFromClientDomain does not leak the attempted URL on failure`() {
    val domain = "this-domain-does-not-exist-xyz123.invalid"
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomain(domain, true)
      }

    assertFalse(ex.message.orEmpty().contains(domain))
    assertFalse(ex.message.orEmpty().contains("http"))
  }

  @Test
  fun `test fetchSigningKeyFromClientDomainBounded still fails normally when the pool is not saturated`() {
    val domain = "this-domain-does-not-exist-xyz123.invalid"
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomainBounded(domain, true)
      }
    assertEquals("Unable to read client_domain's SIGNING_KEY", ex.message)
  }

  @Test
  fun `test fetchSigningKeyFromClientDomainBounded rejects fast when the bounded pool is saturated`() {
    val executor = ClientDomainHelper.CLIENT_DOMAIN_EXECUTOR
    val release = CountDownLatch(1)
    val started = CountDownLatch(executor.maximumPoolSize)
    val blockers =
      (1..executor.maximumPoolSize).map {
        executor.submit {
          started.countDown()
          release.await()
        }
      }
    try {
      assertTrue(started.await(2, TimeUnit.SECONDS))

      val start = System.currentTimeMillis()
      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.fetchSigningKeyFromClientDomainBounded("8.8.8.8", false)
        }
      assertEquals("client_domain resolution unavailable", ex.message)
      val elapsedMs = System.currentTimeMillis() - start
      assertTrue(elapsedMs < 2_500, "expected fast rejection, took ${elapsedMs}ms")
    } finally {
      release.countDown()
      blockers.forEach { it.get(5, TimeUnit.SECONDS) }
    }
  }

  @Test
  fun `test fetch against a tarpit completes quickly, freeing the worker thread`() {
    val serverSocket = ServerSocket(0)
    val host = "127.0.0.1:${serverSocket.localPort}"
    val executor = ClientDomainHelper.CLIENT_DOMAIN_EXECUTOR

    val futures =
      (1..executor.maximumPoolSize).map {
        executor.submit<String> { ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true) }
      }

    try {
      futures.forEach { future ->
        assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
      }
    } finally {
      serverSocket.close()
    }
  }

  @Test
  fun `test extractHostname with plain domain`() {
    assertEquals("example.com", ClientDomainHelper.extractHostname("example.com"))
  }

  @Test
  fun `test extractHostname with domain and port`() {
    assertEquals("example.com", ClientDomainHelper.extractHostname("example.com:8080"))
  }

  @Test
  fun `test extractHostname with bracketed IPv6`() {
    assertEquals("::1", ClientDomainHelper.extractHostname("[::1]"))
  }

  @Test
  fun `test extractHostname with bracketed IPv6 and port`() {
    assertEquals("::1", ClientDomainHelper.extractHostname("[::1]:8080"))
  }

  @Test
  fun `isDomainNameMatch accepts a multi-level wildcard subdomain`() {
    val patterns = listOf("*.sdp.stellar.org")
    assertTrue(ClientDomainHelper.isDomainNameMatch(patterns, "foo.sdp.stellar.org"))
    assertTrue(ClientDomainHelper.isDomainNameMatch(patterns, "a.b.sdp.stellar.org"))
  }

  @Test
  fun `isDomainNameMatch rejects structural characters spanned by a wildcard`() {
    val patterns = listOf("*.sdp.stellar.org")
    listOf(
        "evil.example/x.sdp.stellar.org",
        "evil.example?.sdp.stellar.org",
        "evil.example#.sdp.stellar.org",
        "u@evil.example.sdp.stellar.org",
        "evil.example\\x.sdp.stellar.org",
        "evil.example:80.sdp.stellar.org",
        "a b.sdp.stellar.org",
        "fóo.sdp.stellar.org",
      )
      .forEach {
        assertFalse(ClientDomainHelper.isDomainNameMatch(patterns, it), "should reject: $it")
      }
  }

  @Test
  fun `isDomainNameMatch keeps matching plain exact and wildcard hostnames`() {
    assertTrue(ClientDomainHelper.isDomainNameMatch(listOf("localhost:8080"), "localhost:8080"))
    assertFalse(ClientDomainHelper.isDomainNameMatch(listOf("localhost:8080"), "localhost:8081"))
    assertFalse(ClientDomainHelper.isDomainNameMatch(listOf("stellar.org"), "foo.stellar.org"))
    assertTrue(
      ClientDomainHelper.isDomainNameMatch(listOf("*.stellar.org"), "foo_bar-1.stellar.org")
    )
    assertTrue(
      ClientDomainHelper.isDomainNameMatch(listOf("*.example.com:8080"), "a.example.com:8080")
    )
    assertFalse(
      ClientDomainHelper.isDomainNameMatch(listOf("*.example.com:8080"), "a.example.com:9090")
    )
    assertTrue(
      ClientDomainHelper.isDomainNameMatch(
        listOf("localhost:8080", "*.stellar.org"),
        "x.stellar.org"
      )
    )
  }

  @Test
  fun `isDomainNameMatch rejects a wildcard match with an empty or hyphen-edged label`() {
    val patterns = listOf("*.sdp.stellar.org")
    listOf(
        ".sdp.stellar.org",
        "..sdp.stellar.org",
        "...........sdp.stellar.org",
        "-.sdp.stellar.org",
        "-a.sdp.stellar.org",
        "a-.sdp.stellar.org",
      )
      .forEach {
        assertFalse(ClientDomainHelper.isDomainNameMatch(patterns, it), "should reject: $it")
      }
    assertFalse(
      ClientDomainHelper.isDomainNameMatch(listOf("*.example.com:8080"), ".example.com:8080")
    )
  }

  @Test
  fun `isDomainNameMatch accepts valid host labels under a wildcard`() {
    val patterns = listOf("*.sdp.stellar.org")
    listOf(
        "foo-bar.sdp.stellar.org",
        "a--b.sdp.stellar.org",
        "xn--abc.sdp.stellar.org",
        "_.sdp.stellar.org",
        "foo_bar-1.sdp.stellar.org",
        "a.b.sdp.stellar.org",
      )
      .forEach {
        assertTrue(ClientDomainHelper.isDomainNameMatch(patterns, it), "should accept: $it")
      }
    assertTrue(
      ClientDomainHelper.isDomainNameMatch(listOf("*.example.com:8080"), "a.example.com:8080")
    )
  }

  /** A host of exactly [length] characters made of labels of at most 63, ending in [suffix]. */
  private fun hostOfLength(length: Int, suffix: String): String {
    val labels = mutableListOf<String>()
    var remaining = length - suffix.length
    while (remaining > 0) {
      val take = minOf(63, remaining)
      labels += "a".repeat(take)
      remaining -= take + 1
    }
    val host = labels.joinToString(".") + suffix
    assertEquals(length, host.length)
    return host
  }

  @Test
  fun `isDomainNameMatch rejects a wildcard label longer than 63 characters`() {
    val patterns = listOf("*.sdp.stellar.org")
    listOf("a".repeat(64) + ".sdp.stellar.org", "a." + "a".repeat(64) + ".sdp.stellar.org")
      .forEach {
        assertFalse(ClientDomainHelper.isDomainNameMatch(patterns, it), "should reject: $it")
      }
  }

  @Test
  fun `isDomainNameMatch accepts a wildcard label of exactly 63 characters`() {
    val patterns = listOf("*.sdp.stellar.org")
    listOf("a".repeat(63) + ".sdp.stellar.org", "a." + "a".repeat(63) + ".sdp.stellar.org")
      .forEach {
        assertTrue(ClientDomainHelper.isDomainNameMatch(patterns, it), "should accept: $it")
      }
  }

  @Test
  fun `isDomainNameMatch rejects a wildcard host longer than 253 characters`() {
    val host = hostOfLength(254, ".sdp.stellar.org")
    assertFalse(ClientDomainHelper.isDomainNameMatch(listOf("*.sdp.stellar.org"), host))
  }

  @Test
  fun `isDomainNameMatch accepts a wildcard host of exactly 253 characters`() {
    val host = hostOfLength(253, ".sdp.stellar.org")
    assertTrue(ClientDomainHelper.isDomainNameMatch(listOf("*.sdp.stellar.org"), host))
  }

  @Test
  fun `isDomainNameMatch measures the wildcard host length without the port`() {
    val patterns = listOf("*.example.com:8080")
    assertTrue(
      ClientDomainHelper.isDomainNameMatch(patterns, hostOfLength(253, ".example.com") + ":8080")
    )
    assertFalse(
      ClientDomainHelper.isDomainNameMatch(patterns, hostOfLength(254, ".example.com") + ":8080")
    )
  }

  @Test
  fun `isDomainNameMatch rejects a value breaking a length limit and a label rule together`() {
    val patterns = listOf("*.sdp.stellar.org")
    listOf(
        "-" + "a".repeat(63) + ".sdp.stellar.org",
        "." + hostOfLength(253, ".sdp.stellar.org"),
      )
      .forEach {
        assertFalse(ClientDomainHelper.isDomainNameMatch(patterns, it), "should reject: $it")
      }
  }

  @Test
  fun `isDomainNameMatch keeps exact matching free of length rules`() {
    val longLabel = "a".repeat(64) + ".example.com"
    val longHost = hostOfLength(254, ".example.com")
    assertTrue(ClientDomainHelper.isDomainNameMatch(listOf(longLabel), longLabel))
    assertTrue(ClientDomainHelper.isDomainNameMatch(listOf(longHost), longHost))
  }
}
