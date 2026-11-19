package org.stellar.anchor.util

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
  fun `test bracketed public IPv6 address is accepted`() {
    // The bracketed form is the only one that also succeeds in the real stellar.toml fetch, since
    // OkHttp's request builder (unlike this validation's bare-IPv6 fallback) requires brackets.
    assertDoesNotThrow {
      ClientDomainHelper.validateDomainNotPrivateNetwork("[2001:4860:4860::8888]")
    }
  }

  @Test
  fun `test extractHostname with bare (unbracketed) IPv6 falls back to the bracketed parse`() {
    assertEquals("::1", ClientDomainHelper.extractHostname("::1"))
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

  @Test
  fun `test extractHostname with backslash stops at the private IP instead of the raw string`() {
    assertEquals(
      "10.0.0.5",
      ClientDomainHelper.extractHostname("10.0.0.5\\x.attacker.example"),
    )
  }

  @Test
  fun `test validateDomainNotPrivateNetwork rejects a private IP hidden behind a backslash`() {
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.validateDomainNotPrivateNetwork("10.0.0.5\\x.attacker.example")
      }
    assertEquals("client_domain resolves to a non-public address", ex.message)
  }

  @Test
  fun `test extractHostname with unparsable client_domain fails closed instead of returning the raw string`() {
    val ex = assertThrows(SepException::class.java) { ClientDomainHelper.extractHostname("") }
    assertEquals("client_domain could not be resolved", ex.message)
  }

  @Test
  fun `test validateDomainNotPrivateNetwork rejects an unparsable client_domain`() {
    val ex =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.validateDomainNotPrivateNetwork("")
      }
    assertEquals("client_domain could not be resolved", ex.message)
  }

  @Test
  fun `test extractHostname with backslash does not throw when the resolved host is itself public`() {
    assertEquals(
      "example.com",
      ClientDomainHelper.extractHostname("example.com\\x.attacker.example")
    )
  }

  @Test
  fun `test validateDomainNotPrivateNetwork does not throw when a backslash resolves to a public host`() {
    assertDoesNotThrow {
      ClientDomainHelper.validateDomainNotPrivateNetwork("example.com\\x.attacker.example")
    }
  }

  @Test
  fun `test extractHostname with trailing whitespace fails closed instead of silently trimming`() {
    // Pins the decision to parse the exact request URL (scheme, authority, AND the stellar.toml
    // path) rather than just "https://" + clientDomain: the trailing space is only invalid once a
    // path follows it, so a prefix-only parse would wrongly accept this as host "example.com".
    assertThrows(SepException::class.java) { ClientDomainHelper.extractHostname("example.com ") }
  }

  @Test
  fun `test fetchSigningKeyFromClientDomain on mainnet is blocked from reaching a loopback server via backslash`() {
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
          "127.0.0.1:${server.address.port}\\x.attacker.invalid",
          false,
        )
      }
    } finally {
      server.stop(0)
    }
  }

  // --- client_domain name lookups run on the bounded resolver (ANCHOR-1494) ---

  private val publicAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

  /** Blocks like a native resolver call: an interrupt does not release it, only [release] does. */
  private fun blockIgnoringInterrupts(release: CountDownLatch) {
    while (true) {
      try {
        release.await()
        return
      } catch (e: InterruptedException) {
        // a native lookup ignores the interrupt, so the stub does too
      }
    }
  }

  private fun <T> withResolver(lookup: BoundedDnsResolver.Lookup, block: () -> T): T {
    val original = ClientDomainHelper.dnsResolver
    ClientDomainHelper.dnsResolver = BoundedDnsResolver(lookup)
    try {
      return block()
    } finally {
      ClientDomainHelper.dnsResolver = original
    }
  }

  private fun closedLoopbackHost(): String {
    val port = ServerSocket(0).use { it.localPort }
    return "127.0.0.1:$port"
  }

  /** Leaves every resolver worker held by a lookup that only [release] frees. */
  private fun saturateResolver(release: CountDownLatch) {
    val resolver = ClientDomainHelper.dnsResolver
    val callers = Executors.newFixedThreadPool(8)
    try {
      (0 until 8)
        .map {
          callers.submit {
            try {
              resolver.resolve("stuck$it.example.com")
            } catch (e: UnknownHostException) {
              // timing out is how the lookup is left stuck
            }
          }
        }
        .forEach { it.get(5, TimeUnit.SECONDS) }
    } finally {
      callers.shutdownNow()
    }
  }

  @Test
  fun `test validateDomainNotPrivateNetwork gives up on a lookup that never completes`() {
    val release = CountDownLatch(1)
    try {
      withResolver({ blockIgnoringInterrupts(release).let { arrayOf(publicAddress) } }) {
        val start = System.nanoTime()
        val ex =
          assertThrows(SepException::class.java) {
            ClientDomainHelper.validateDomainNotPrivateNetwork("stuck.example.com")
          }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertEquals("client_domain resolution unavailable", ex.message)
        assertTrue(elapsedMs < 1_500, "expected failure within 1,500 ms, took ${elapsedMs}ms")
      }
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `test fetchSigningKeyFromClientDomain on mainnet gives up on a validating lookup that never completes`() {
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val caller = Executors.newSingleThreadExecutor()
    try {
      // the first lookup (the private-network check) answers; the one OkHttp makes then hangs
      withResolver({
        if (calls.getAndIncrement() == 0) {
          arrayOf(publicAddress)
        } else {
          blockIgnoringInterrupts(release)
          arrayOf(publicAddress)
        }
      }) {
        val start = System.nanoTime()
        val future =
          caller.submit<String> {
            ClientDomainHelper.fetchSigningKeyFromClientDomain("two-step.example.com", false)
          }
        val ex = assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertEquals("Unable to read client_domain's SIGNING_KEY", ex.cause?.message)
        assertEquals(2, calls.get())
        assertTrue(elapsedMs < 1_500, "expected failure within 1,500 ms, took ${elapsedMs}ms")
      }
    } finally {
      release.countDown()
      caller.shutdownNow()
    }
  }

  @Test
  fun `test eight bounded fetches whose lookups never complete leave the fetch pool free`() {
    val release = CountDownLatch(1)
    val callers = Executors.newFixedThreadPool(8)
    try {
      withResolver({ blockIgnoringInterrupts(release).let { arrayOf(publicAddress) } }) {
        val start = System.nanoTime()
        val results =
          (0 until 8).map { i ->
            callers.submit<String?> {
              try {
                ClientDomainHelper.fetchSigningKeyFromClientDomainBounded(
                  "stuck$i.example.com",
                  false
                )
                null
              } catch (e: SepException) {
                e.message
              }
            }
          }
        results.forEach {
          assertEquals("client_domain resolution unavailable", it.get(3, TimeUnit.SECONDS))
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(elapsedMs < 2_500, "expected all 8 to fail within 2,500 ms, took ${elapsedMs}ms")

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_500)
        while (
          ClientDomainHelper.CLIENT_DOMAIN_EXECUTOR.activeCount > 0 && System.nanoTime() < deadline
        ) {
          Thread.sleep(10)
        }
        assertEquals(0, ClientDomainHelper.CLIENT_DOMAIN_EXECUTOR.activeCount)
      }
    } finally {
      release.countDown()
      callers.shutdownNow()
    }
  }

  @Test
  fun `test a testnet fetch is not held up while every resolver worker is stuck`() {
    val release = CountDownLatch(1)
    try {
      withResolver({ blockIgnoringInterrupts(release).let { arrayOf(publicAddress) } }) {
        saturateResolver(release)

        val start = System.nanoTime()
        val ex =
          assertThrows(SepException::class.java) {
            ClientDomainHelper.fetchSigningKeyFromClientDomainBounded(closedLoopbackHost(), true)
          }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertEquals("Unable to read client_domain's SIGNING_KEY", ex.message)
        assertTrue(elapsedMs < 2_500, "expected failure within 2,500 ms, took ${elapsedMs}ms")
      }
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `test a mainnet fetch fails closed while every resolver worker is stuck`() {
    val release = CountDownLatch(1)
    try {
      withResolver({ blockIgnoringInterrupts(release).let { arrayOf(publicAddress) } }) {
        saturateResolver(release)

        val ex =
          assertThrows(SepException::class.java) {
            ClientDomainHelper.fetchSigningKeyFromClientDomainBounded("another.example.com", false)
          }

        assertEquals("client_domain resolution unavailable", ex.message)
      }
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `test a testnet fetch never calls the resolver`() {
    val calls = AtomicInteger()
    withResolver({
      calls.incrementAndGet()
      arrayOf(publicAddress)
    }) {
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomain(closedLoopbackHost(), true)
      }
    }

    assertEquals(0, calls.get())
  }

  @Test
  fun `test validateDomainNotPrivateNetwork still rejects a private address from the resolver`() {
    withResolver({ arrayOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5))) }) {
      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.validateDomainNotPrivateNetwork("internal.example.com")
        }

      assertEquals("client_domain resolves to a non-public address", ex.message)
    }
  }

  @Test
  fun `test validateDomainNotPrivateNetwork still reports an unresolvable name`() {
    withResolver({ throw UnknownHostException("nxdomain") }) {
      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.validateDomainNotPrivateNetwork("missing.example.com")
        }

      assertEquals("client_domain could not be resolved", ex.message)
    }
  }
}
