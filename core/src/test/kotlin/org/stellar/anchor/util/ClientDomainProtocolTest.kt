package org.stellar.anchor.util

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.SepException
import org.stellar.sdk.KeyPair

/**
 * HackerOne #4218706: the client_domain fetch must never carry HTTP/2 frames. The tests run the
 * production fetch clients against a raw TLS server that records the ALPN protocols the client
 * offered and the one that was negotiated, so they assert what goes on the wire, not how the client
 * is built.
 */
class ClientDomainProtocolTest {
  private val signingKey = KeyPair.random().accountId
  private val servers = CopyOnWriteArrayList<AlpnTlsServer>()

  private val serverCertificate: HeldCertificate =
    HeldCertificate.Builder().commonName("127.0.0.1").addSubjectAlternativeName("127.0.0.1").build()
  private val serverHandshake: HandshakeCertificates =
    HandshakeCertificates.Builder().heldCertificate(serverCertificate).build()
  private val clientHandshake: HandshakeCertificates =
    HandshakeCertificates.Builder().addTrustedCertificate(serverCertificate.certificate).build()

  @AfterEach
  fun closeServers() {
    servers.forEach { it.close() }
  }

  /**
   * The production fetch client for the branch, with the test trust store and a DNS that allows the
   * loopback address. Protocols, timeouts, redirects and retry settings are the production ones.
   */
  private fun productionClient(allowHttpRetry: Boolean): OkHttpClient =
    ClientDomainHelper.fetchClient(allowHttpRetry)
      .newBuilder()
      .sslSocketFactory(clientHandshake.sslSocketFactory(), clientHandshake.trustManager)
      .dns(Dns.SYSTEM)
      .build()

  private fun startServer(
    serverAlpn: List<String>,
    floodMillis: Long = 0,
    keepAlive: Boolean = false,
  ): AlpnTlsServer {
    val server = AlpnTlsServer(serverHandshake, serverAlpn, signingKey, floodMillis, keepAlive)
    servers.add(server)
    return server
  }

  // CDPROTO-01
  @Test
  fun `both fetch clients offer HTTP 1_1 only`() {
    assertEquals(listOf(Protocol.HTTP_1_1), ClientDomainHelper.fetchClient(false).protocols)
    assertEquals(listOf(Protocol.HTTP_1_1), ClientDomainHelper.fetchClient(true).protocols)
  }

  // CDPROTO-02
  @Test
  fun `mainnet fetch client is served over HTTP 1_1 by a host that offers h2`() {
    val server = startServer(listOf("h2", "http/1.1"))

    val protocol = fetchProtocol(productionClient(false), server)

    assertEquals(Protocol.HTTP_1_1, protocol)
    assertEquals(listOf(listOf("http/1.1")), server.offeredByClient)
    assertEquals(listOf("http/1.1"), server.negotiated)
  }

  // CDPROTO-03
  @Test
  fun `shared fetch client is served over HTTP 1_1 by a host that offers h2`() {
    val server = startServer(listOf("h2", "http/1.1"))

    val protocol = fetchProtocol(productionClient(true), server)

    assertEquals(Protocol.HTTP_1_1, protocol)
    assertEquals(listOf(listOf("http/1.1")), server.offeredByClient)
    assertEquals(listOf("http/1.1"), server.negotiated)
  }

  // CDPROTO-04
  @Test
  fun `a host that floods HTTP 2 frames is served over HTTP 1_1 and does not slow an honest fetch`() {
    val floodHost = startServer(listOf("h2", "http/1.1"), floodMillis = 3_000, keepAlive = true)
    val honestHost = startServer(listOf("http/1.1"))
    val client = productionClient(true)

    val floodedKey =
      ClientDomainHelper.fetchSigningKeyFromClientDomain(
        "127.0.0.1:${floodHost.port}",
        true,
        client
      )
    // The flood host's connection is pooled and open (keep-alive) while the honest fetch runs.
    val startedAt = System.nanoTime()
    val honestKey =
      ClientDomainHelper.fetchSigningKeyFromClientDomain(
        "127.0.0.1:${honestHost.port}",
        true,
        client,
      )
    val honestMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    assertEquals(signingKey, floodedKey)
    assertEquals(listOf("http/1.1"), floodHost.negotiated)
    assertFalse(floodHost.negotiated.contains("h2"))
    assertEquals(signingKey, honestKey)
    assertTrue(honestMillis < 1_500, "honest fetch took $honestMillis ms")
  }

  // CDPROTO-05
  @Test
  fun `a host that accepts only h2 cannot be fetched`() {
    val server = startServer(listOf("h2"))
    val client = productionClient(true)

    val startedAt = System.nanoTime()
    val e =
      assertThrows(SepException::class.java) {
        ClientDomainHelper.fetchSigningKeyFromClientDomain("127.0.0.1:${server.port}", true, client)
      }
    val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    assertEquals("Unable to read client_domain's SIGNING_KEY", e.message)
    assertTrue(millis < 2_500, "fetch took $millis ms")
    assertTrue(server.negotiated.isEmpty(), "negotiated ${server.negotiated}")
  }

  // CDPROTO-06
  @Test
  fun `a valid stellar toml over TLS HTTP 1_1 returns its SIGNING_KEY`() {
    val server = startServer(listOf("http/1.1"))

    val key =
      ClientDomainHelper.fetchSigningKeyFromClientDomain(
        "127.0.0.1:${server.port}",
        false,
        productionClient(false),
      )

    assertEquals(signingKey, key)
    assertEquals(listOf("http/1.1"), server.negotiated)
  }

  // CDPROTO-08
  @Test
  fun `both fetch clients keep their timeouts redirect and retry settings`() {
    for (allowHttpRetry in listOf(true, false)) {
      val client = ClientDomainHelper.fetchClient(allowHttpRetry)
      assertEquals(1_000, client.callTimeoutMillis)
      assertEquals(5_000, client.connectTimeoutMillis)
      assertEquals(10_000, client.readTimeoutMillis)
      assertFalse(client.followRedirects)
      assertFalse(client.followSslRedirects)
      assertFalse(client.retryOnConnectionFailure)
    }
  }

  private fun fetchProtocol(client: OkHttpClient, server: AlpnTlsServer): Protocol {
    val request = Request.Builder().url("https://127.0.0.1:${server.port}/").build()
    client.newCall(request).execute().use {
      return it.protocol
    }
  }
}

/**
 * A TLS server on loopback with a configurable ALPN preference. It records the protocols the client
 * offered and the one negotiated per connection. When `http/1.1` is negotiated it answers every
 * request with a stellar.toml carrying [signingKey]; when `h2` is negotiated it writes empty
 * SETTINGS and PING frames without reading, for [floodMillis]. A client that offers no protocol the
 * server accepts is rejected in the handshake.
 */
internal class AlpnTlsServer(
  handshake: HandshakeCertificates,
  private val serverAlpn: List<String>,
  private val signingKey: String,
  private val floodMillis: Long,
  private val keepAlive: Boolean,
) {
  val offeredByClient = CopyOnWriteArrayList<List<String>>()
  val negotiated = CopyOnWriteArrayList<String>()

  private val serverSocket: SSLServerSocket =
    handshake
      .sslContext()
      .serverSocketFactory
      .createServerSocket(
        0,
        50,
        InetAddress.getByName("127.0.0.1"),
      ) as SSLServerSocket
  private val openSockets = CopyOnWriteArrayList<Socket>()

  val port: Int
    get() = serverSocket.localPort

  init {
    Thread({ acceptLoop() }, "alpn-tls-server-$port").apply {
      isDaemon = true
      start()
    }
  }

  private fun acceptLoop() {
    while (!serverSocket.isClosed) {
      val socket =
        try {
          serverSocket.accept() as SSLSocket
        } catch (e: Exception) {
          return
        }
      openSockets.add(socket)
      Thread({ serve(socket) }, "alpn-tls-conn-$port").apply {
        isDaemon = true
        start()
      }
    }
  }

  private fun serve(socket: SSLSocket) {
    try {
      socket.handshakeApplicationProtocolSelector =
        java.util.function.BiFunction { _, offered ->
          offeredByClient.add(offered.toList())
          serverAlpn.firstOrNull { offered.contains(it) }
        }
      socket.startHandshake()
      val protocol = socket.applicationProtocol
      negotiated.add(protocol)
      if (protocol == "h2") flood(socket) else answerHttp11(socket)
    } catch (e: Exception) {
      // A rejected handshake or a closed socket ends the connection; nothing to record.
    } finally {
      if (!keepAlive) runCatching { socket.close() }
    }
  }

  private fun answerHttp11(socket: SSLSocket) {
    val input = socket.inputStream
    val output = socket.outputStream
    val body = "SIGNING_KEY=\"$signingKey\"\n".toByteArray()
    do {
      readUntilBlankLine(input) ?: return
      output.write(
        ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n")
          .toByteArray()
      )
      output.write(body)
      output.flush()
    } while (keepAlive)
  }

  private fun readUntilBlankLine(input: java.io.InputStream): Unit? {
    var matched = 0
    val end =
      byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    while (matched < end.size) {
      val b = input.read()
      if (b < 0) return null
      matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
    }
    return Unit
  }

  private fun flood(socket: SSLSocket) {
    val settings = byteArrayOf(0, 0, 0, 4, 0, 0, 0, 0, 0)
    val ping = byteArrayOf(0, 0, 8, 6, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)
    val output = socket.outputStream
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(floodMillis)
    while (System.nanoTime() < deadline) {
      output.write(settings)
      output.write(ping)
      output.flush()
    }
  }

  fun close() {
    runCatching { serverSocket.close() }
    openSockets.forEach { runCatching { it.close() } }
  }
}
