package org.stellar.anchor.util

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.TestConstants.Companion.TEST_CLIENT_TOML
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
  fun `test hanging DNS for client_domain does not keep the bounded pool saturated`() {
    val hanging = BoundedDnsResolver(500, StubHangingResolver())
    val original = ClientDomainHelper.dns
    ClientDomainHelper.dns = hanging
    val executor = ClientDomainHelper.CLIENT_DOMAIN_EXECUTOR
    val callers = Executors.newFixedThreadPool(executor.maximumPoolSize)
    try {
      val start = System.nanoTime()
      val attacks =
        (1..executor.maximumPoolSize).map { i ->
          callers.submit<String?> {
            try {
              ClientDomainHelper.fetchSigningKeyFromClientDomainBounded(
                "a$i.hang.example.com",
                false,
              )
            } catch (e: SepException) {
              e.message
            }
          }
        }
      attacks.forEach {
        assertEquals("client_domain could not be resolved", it.get(5, TimeUnit.SECONDS))
      }
      val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      assertTrue(elapsedMs < 2_500, "expected bounded resolution, took ${elapsedMs}ms")
      assertEquals(0, executor.activeCount)

      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.fetchSigningKeyFromClientDomainBounded("next.hang.example.com", false)
        }
      assertEquals("client_domain could not be resolved", ex.message)
    } finally {
      ClientDomainHelper.dns = original
      callers.shutdownNow()
    }
  }

  @Test
  fun `test hanging DNS on the non-public network path is also bounded`() {
    val original = ClientDomainHelper.dns
    ClientDomainHelper.dns = BoundedDnsResolver(500, StubHangingResolver())
    try {
      val start = System.nanoTime()
      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.fetchSigningKeyFromClientDomain("a.hang.example.com", true)
        }
      assertEquals("Unable to read client_domain's SIGNING_KEY", ex.message)
      val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
      assertTrue(elapsedMs < 2_500, "expected bounded resolution, took ${elapsedMs}ms")
    } finally {
      ClientDomainHelper.dns = original
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

  // ---- ANCHOR-1533: the fetched client_domain document is never handed to toml4j

  private val clientSigningKey = "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F"

  private fun withTomlServer(status: Int = 200, body: ByteArray, block: (String) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/.well-known/stellar.toml") { exchange ->
      exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
      if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
      exchange.close()
    }
    server.start()
    try {
      block("127.0.0.1:${server.address.port}")
    } finally {
      server.stop(0)
    }
  }

  private fun attackHeader(shape: String, n: Int): String =
    when (shape) {
      "unclosed quote" -> "[\"'" + ".c".repeat(n) + "]"
      "escaped quote in the middle" -> "[a.\"\\\"\"" + ".c".repeat(n) + "]"
      "leading escaped quote" -> "[\"\\\"\"" + ".x".repeat(n) + "]"
      "escape before closing bracket" -> "[\"a\\\"]\"" + ".x".repeat(n) + "]"
      "inline table" -> "x = {a" + ".a".repeat(n) + " = {}}"
      else -> throw IllegalArgumentException(shape)
    }

  private val attackShapes =
    listOf(
      "unclosed quote",
      "escaped quote in the middle",
      "leading escaped quote",
      "escape before closing bracket",
      "inline table",
    )

  @Test
  fun `test fetchSigningKeyFromClientDomain returns the key from a 98 KB document with an attack header`() {
    for (shape in attackShapes) {
      val doc = "SIGNING_KEY=\"$clientSigningKey\"\n${attackHeader(shape, 49_000)}\n"
      assertTrue(doc.length in 98_000..100_300, "$shape document is ${doc.length} bytes")
      withTomlServer(body = doc.toByteArray()) { host ->
        assertEquals(
          clientSigningKey,
          ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true),
          shape,
        )
      }
    }
  }

  @Test
  fun `test fetchSigningKeyFromClientDomain reports a missing key for a 98 KB attack document within 2500 ms`() {
    for (shape in attackShapes) {
      val doc = attackHeader(shape, 49_000) + "\n"
      assertTrue(doc.length in 98_000..100_300, "$shape document is ${doc.length} bytes")
      withTomlServer(body = doc.toByteArray()) { host ->
        val start = System.nanoTime()
        val ex =
          assertThrows(SepException::class.java) {
            ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true)
          }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertEquals("SIGNING_KEY not present in 'client_domain' TOML", ex.message, shape)
        assertTrue(ms < 2_500, "$shape took ${ms}ms")
      }
    }
  }

  @Test
  fun `test fetchSigningKeyFromClientDomain reads the key from the standard client toml`() {
    withTomlServer(body = TEST_CLIENT_TOML.toByteArray()) { host ->
      assertEquals(
        "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F",
        ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true),
      )
    }
  }

  @Test
  fun `test fetchSigningKeyFromClientDomain rejects a SIGNING_KEY that is not a Stellar account id`() {
    withTomlServer(body = "SIGNING_KEY=\"BADKEY\"\n".toByteArray()) { host ->
      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true)
        }
      assertEquals("SIGNING_KEY BADKEY is not a valid Stellar account Id.", ex.message)
    }
  }

  @Test
  fun `test fetchSigningKeyFromClientDomain keeps the unable to read message for a failed fetch`() {
    withTomlServer(status = 500, body = ByteArray(0)) { host ->
      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true)
        }
      assertEquals("Unable to read client_domain's SIGNING_KEY", ex.message)
    }
    val tooBig = "SIGNING_KEY=\"$clientSigningKey\"\n" + "#".repeat(101 * 1024)
    withTomlServer(body = tooBig.toByteArray()) { host ->
      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true)
        }
      assertEquals("Unable to read client_domain's SIGNING_KEY", ex.message)
    }
  }

  // HackerOne #4209933: the report's one-long-header document, fetched from a local server.
  private val notPresent = "SIGNING_KEY not present in 'client_domain' TOML"
  private val hostileBody = ClientDomainTomlFixtures.reportDocument().toByteArray()

  private fun <T> timed(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val result = block()
    return Pair(result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
  }

  @Test
  fun `test fetch of the one-long-header document without a key fails as missing within 2500 ms`() {
    ClientDomainTomlFixtures.withTomlServer(hostileBody) { host ->
      val (ex, ms) =
        timed {
          assertThrows(SepException::class.java) {
            ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true)
          }
        }
      assertEquals(notPresent, ex.message)
      assertTrue(ms < 2_500, "took ${ms}ms")
    }
  }

  @Test
  fun `test fetch of a key followed by the one-long-header document returns the key within 2500 ms`() {
    val body =
      ClientDomainTomlFixtures.withSigningKeyFirst(
          ClientDomainTomlFixtures.VALID_SIGNING_KEY,
          ClientDomainTomlFixtures.reportDocument(),
        )
        .toByteArray()
    ClientDomainTomlFixtures.withTomlServer(body) { host ->
      val (key, ms) = timed { ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true) }
      assertEquals(ClientDomainTomlFixtures.VALID_SIGNING_KEY, key)
      assertTrue(ms < 2_500, "took ${ms}ms")
    }
  }

  @Test
  fun `test fetch of an ordinary 99944-byte document returns its key within 2500 ms`() {
    val doc = ClientDomainTomlFixtures.ordinaryDocument(ClientDomainTomlFixtures.VALID_SIGNING_KEY)
    assertEquals(99_944, doc.length)
    ClientDomainTomlFixtures.withTomlServer(doc.toByteArray()) { host ->
      val (key, ms) = timed { ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true) }
      assertEquals(ClientDomainTomlFixtures.VALID_SIGNING_KEY, key)
      assertTrue(ms < 2_500, "took ${ms}ms")
    }
  }

  @Test
  fun `test eight sequential hostile requests do not stop a well-behaved wallet`() {
    ClientDomainTomlFixtures.withTomlServer(hostileBody) { hostile ->
      ClientDomainTomlFixtures.withTomlServer(TEST_CLIENT_TOML.toByteArray()) { wallet ->
        repeat(8) { i ->
          val ex =
            assertThrows(SepException::class.java) {
              ClientDomainHelper.fetchSigningKeyFromClientDomainBounded(hostile, true)
            }
          assertEquals(notPresent, ex.message, "hostile request ${i + 1}")
        }
        val (key, ms) =
          timed { ClientDomainHelper.fetchSigningKeyFromClientDomainBounded(wallet, true) }
        assertEquals(ClientDomainTomlFixtures.VALID_SIGNING_KEY, key)
        assertTrue(ms < 2_500, "well-behaved request took ${ms}ms")
      }
    }
  }

  /** Eight hostile requests at the same time (the pool's maximum); returns each one's message. */
  private fun eightConcurrentHostileRequests(hostile: String): List<String?> {
    val callers = Executors.newFixedThreadPool(8)
    try {
      val start = CountDownLatch(1)
      val results =
        (1..8).map {
          callers.submit<String?> {
            start.await()
            try {
              ClientDomainHelper.fetchSigningKeyFromClientDomainBounded(hostile, true)
            } catch (e: SepException) {
              e.message
            }
          }
        }
      start.countDown()
      return results.map { it.get(10, TimeUnit.SECONDS) }
    } finally {
      callers.shutdownNow()
    }
  }

  @Test
  fun `test eight concurrent hostile requests are each answered as a missing key, none as unavailable`() {
    ClientDomainTomlFixtures.withTomlServer(hostileBody) { hostile ->
      val messages = eightConcurrentHostileRequests(hostile)
      assertEquals(List(8) { notPresent }, messages)
    }
  }

  @Test
  fun `test the client domain workers are idle after eight concurrent hostile requests`() {
    val executor = ClientDomainHelper.CLIENT_DOMAIN_EXECUTOR
    ClientDomainTomlFixtures.withTomlServer(hostileBody) { hostile ->
      ClientDomainTomlFixtures.withTomlServer(TEST_CLIENT_TOML.toByteArray()) { wallet ->
        eightConcurrentHostileRequests(hostile)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (executor.activeCount > 0 && System.nanoTime() < deadline) {
          Thread.sleep(10)
        }
        assertEquals(0, executor.activeCount)
        assertEquals(
          ClientDomainTomlFixtures.VALID_SIGNING_KEY,
          ClientDomainHelper.fetchSigningKeyFromClientDomainBounded(wallet, true),
        )
      }
    }
  }

  @Test
  fun `test a one-long-header document of exactly 102400 bytes is read and reported as missing`() {
    val body = ClientDomainTomlFixtures.documentOfExactSize(102_400).toByteArray()
    assertEquals(102_400, body.size)
    ClientDomainTomlFixtures.withTomlServer(body) { host ->
      val (ex, ms) =
        timed {
          assertThrows(SepException::class.java) {
            ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true)
          }
        }
      assertEquals(notPresent, ex.message)
      assertTrue(ms < 2_500, "took ${ms}ms")
    }
  }

  @Test
  fun `test a one-long-header document of 102401 bytes is refused as unreadable`() {
    val body = ClientDomainTomlFixtures.documentOfExactSize(102_401).toByteArray()
    assertEquals(102_401, body.size)
    ClientDomainTomlFixtures.withTomlServer(body) { host ->
      val ex =
        assertThrows(SepException::class.java) {
          ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true)
        }
      assertEquals("Unable to read client_domain's SIGNING_KEY", ex.message)
    }
  }

  // HackerOne #4210811: the inline-table document (step 2) and the escaped-quote header (step 4),
  // served at the reporter's size.
  private fun assertFetchMissing(document: String) {
    ClientDomainTomlFixtures.withTomlServer(document.toByteArray()) { host ->
      val (ex, ms) =
        timed {
          assertThrows(SepException::class.java) {
            ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true)
          }
        }
      assertEquals(notPresent, ex.message)
      assertTrue(ms < 2_500, "took ${ms}ms")
    }
  }

  private fun assertFetchReturnsKey(document: String) {
    val body =
      ClientDomainTomlFixtures.withSigningKeyFirst(
          ClientDomainTomlFixtures.VALID_SIGNING_KEY,
          document,
        )
        .toByteArray()
    ClientDomainTomlFixtures.withTomlServer(body) { host ->
      val (key, ms) = timed { ClientDomainHelper.fetchSigningKeyFromClientDomain(host, true) }
      assertEquals(ClientDomainTomlFixtures.VALID_SIGNING_KEY, key)
      assertTrue(ms < 2_500, "took ${ms}ms")
    }
  }

  @Test
  fun `test fetch of the inline-table document without a key fails as missing within 2500 ms`() {
    val doc = ClientDomainTomlFixtures.inlineTableDocument()
    assertEquals(100_009, doc.length)
    assertFetchMissing(doc)
  }

  @Test
  fun `test fetch of a key followed by the inline-table document returns the key within 2500 ms`() {
    assertFetchReturnsKey(ClientDomainTomlFixtures.inlineTableDocument())
  }

  @Test
  fun `test fetch of the escaped-quote header document without a key fails as missing within 2500 ms`() {
    val doc = ClientDomainTomlFixtures.escapedQuoteHeaderDocument()
    assertEquals(100_010, doc.length)
    assertFetchMissing(doc)
  }

  @Test
  fun `test fetch of a key followed by the escaped-quote header document returns the key within 2500 ms`() {
    assertFetchReturnsKey(ClientDomainTomlFixtures.escapedQuoteHeaderDocument())
  }
}

private class StubHangingResolver : org.xbill.DNS.Resolver {
  override fun setPort(port: Int) {}

  override fun setTCP(flag: Boolean) {}

  override fun setIgnoreTruncation(flag: Boolean) {}

  override fun setEDNS(
    version: Int,
    payloadSize: Int,
    flags: Int,
    options: MutableList<org.xbill.DNS.EDNSOption>?,
  ) {}

  override fun setTSIGKey(key: org.xbill.DNS.TSIG?) {}

  override fun setTimeout(timeout: java.time.Duration) {}

  override fun sendAsync(
    query: org.xbill.DNS.Message
  ): java.util.concurrent.CompletionStage<org.xbill.DNS.Message> =
    java.util.concurrent.CompletableFuture()

  override fun sendAsync(
    query: org.xbill.DNS.Message,
    executor: java.util.concurrent.Executor,
  ): java.util.concurrent.CompletionStage<org.xbill.DNS.Message> = sendAsync(query)
}
