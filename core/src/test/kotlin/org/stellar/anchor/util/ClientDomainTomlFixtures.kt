package org.stellar.anchor.util

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Documents and a local server for the client_domain stellar.toml tests of HackerOne report
 *
 * #4209933: a table header made of one very long segment followed by thousands of `cN={}`
 * inline-table lines, which kept toml4j parsing for minutes.
 */
object ClientDomainTomlFixtures {
  const val VALID_SIGNING_KEY = "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F"

  /** The fetch cap in `Sep1Helper` (`100 * 1024`). */
  const val FETCH_CAP_BYTES = 102_400

  /**
   * The report's document: line 1 is `[` + [headerLength] `A` + `]`, then `c0={}`, `c1={}`, ...
   * until the document is at least [minBytes] long. Every character is ASCII, so characters equal
   * bytes.
   */
  fun reportDocument(
    headerLength: Int = 68_000,
    minBytes: Int = 100_000,
    maxBytes: Int = FETCH_CAP_BYTES,
  ): String {
    val sb = StringBuilder()
    sb.append('[').append("A".repeat(headerLength)).append("]\n")
    var i = 0
    while (sb.length < minBytes) {
      sb.append("c").append(i++).append("={}\n")
    }
    check(sb.length <= maxBytes) { "document is ${sb.length} bytes, above $maxBytes" }
    return sb.toString()
  }

  /** `SIGNING_KEY = "<key>"` on the first line, then [document]. */
  fun withSigningKeyFirst(key: String, document: String): String =
    "SIGNING_KEY = \"$key\"\n$document"

  /** A key line and `cN={}` lines with no table header, [minBytes] long or more. */
  fun inlineTablesOnly(key: String, minBytes: Int = 100_000): String {
    val sb = StringBuilder("SIGNING_KEY = \"$key\"\n")
    var i = 0
    while (sb.length < minBytes) {
      sb.append("c").append(i++).append("={}\n")
    }
    return sb.toString()
  }

  /** An ordinary stellar.toml of exactly [bytes] bytes with its key at the top. */
  fun ordinaryDocument(key: String, bytes: Int = 99_944): String {
    val sb = StringBuilder()
    sb.append("SIGNING_KEY = \"$key\"\n")
    sb.append("VERSION = \"2.0.0\"\n")
    sb.append("NETWORK_PASSPHRASE = \"Test SDF Network ; September 2015\"\n\n")
    sb.append("[DOCUMENTATION]\n")
    sb.append("ORG_NAME = \"Test Org\"\n")
    sb.append("ORG_URL = \"https://example.com\"\n\n")
    var i = 0
    while (sb.length + CURRENCY_BLOCK_MAX < bytes) {
      sb.append("[[CURRENCIES]]\n")
      sb.append("code = \"TST$i\"\n")
      sb.append("issuer = \"$key\"\n")
      sb.append("desc = \"An ordinary currency entry\"\n\n")
      i++
    }
    padWithComments(sb, bytes)
    check(sb.length == bytes) { "document is ${sb.length} bytes, expected $bytes" }
    return sb.toString()
  }

  /** The report's document padded with comment lines to exactly [bytes] bytes. */
  fun documentOfExactSize(bytes: Int, headerLength: Int = 68_000): String {
    val sb = StringBuilder()
    sb.append('[').append("A".repeat(headerLength)).append("]\n")
    var i = 0
    while (true) {
      val line = "c" + i + "={}\n"
      if (sb.length + line.length > bytes) break
      sb.append(line)
      i++
    }
    padWithComments(sb, bytes)
    check(sb.length == bytes) { "document is ${sb.length} bytes, expected $bytes" }
    return sb.toString()
  }

  /** Tasks `CLIENT_DOMAIN_EXECUTOR` has completed so far (the bounded pool's observable). */
  fun clientDomainCompletedTasks(): Long =
    ClientDomainHelper.CLIENT_DOMAIN_EXECUTOR.completedTaskCount

  /**
   * True when `CLIENT_DOMAIN_EXECUTOR` has completed at least one task more than [before] within
   * [timeoutMs]. A fetch through `fetchSigningKeyFromClientDomainBounded` runs as a pool task, so
   * this tells it from a direct call to `fetchSigningKeyFromClientDomain`, which does not.
   */
  fun awaitClientDomainTaskCompleted(before: Long, timeoutMs: Long = 1_000): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (clientDomainCompletedTasks() <= before) {
      if (System.nanoTime() >= deadline) return false
      Thread.sleep(10)
    }
    return true
  }

  /**
   * Serves [body] at `/.well-known/stellar.toml` on `127.0.0.1` with status 200, calls [block] with
   * `127.0.0.1:<port>` and always stops the server. Every request increments [requests].
   */
  fun withTomlServer(
    body: ByteArray,
    requests: AtomicInteger? = null,
    block: (String) -> Unit,
  ) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/.well-known/stellar.toml") { exchange ->
      requests?.incrementAndGet()
      exchange.sendResponseHeaders(200, if (body.isEmpty()) -1 else body.size.toLong())
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

  private const val CURRENCY_BLOCK_MAX = 200

  private fun padWithComments(sb: StringBuilder, bytes: Int) {
    while (bytes - sb.length > 80) {
      sb.append('#').append("x".repeat(78)).append('\n')
    }
    val remaining = bytes - sb.length
    if (remaining == 1) {
      sb.append('\n')
    } else if (remaining > 1) {
      sb.append("#".repeat(remaining - 1)).append('\n')
    }
  }
}
