package org.stellar.anchor.platform.utils

import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.stellar.anchor.util.Log

/**
 * `Origin` is matched by Spring against a backtracking pattern before any `@CrossOrigin` handler
 * runs (HackerOne #4240773). The filter refuses everything that is not `null` or a plain
 * `scheme://host[:port]` before that happens. These tests run the filter alone, so Spring's parser
 * is never reached, not even for the 8 KB value.
 */
class OriginHeaderFilterTest {
  private val rejection = """{"error":"Invalid Origin header."}"""

  /** The reporter's value: `//`, N x `@`, `#`, U+0085. */
  private fun attackOrigin(ats: Int) = "//" + "@".repeat(ats) + "#" + '\u0085'

  private class Outcome(val response: MockHttpServletResponse, val chain: MockFilterChain) {
    val chainCalled: Boolean
      get() = chain.request != null
  }

  private fun send(vararg origins: String, method: String = "GET", name: String = "Origin") =
    MockHttpServletRequest(method, "/.well-known/stellar.toml")
      .also { request -> origins.forEach { request.addHeader(name, it) } }
      .let { run(it) }

  private fun run(request: MockHttpServletRequest): Outcome {
    val response = MockHttpServletResponse()
    val chain = MockFilterChain()
    OriginHeaderFilter().doFilter(request, response, chain)
    return Outcome(response, chain)
  }

  private fun assertRefused(outcome: Outcome, what: String) {
    assertEquals(400, outcome.response.status, "$what: status")
    assertEquals(rejection, outcome.response.contentAsString, "$what: body")
    assertTrue(outcome.response.contentType!!.startsWith("application/json"), "$what: type")
    assertFalse(outcome.chainCalled, "$what: the chain was called")
  }

  private fun assertPassed(outcome: Outcome, what: String) {
    assertTrue(outcome.chainCalled, "$what: the chain was not called")
    assertEquals(200, outcome.response.status, "$what: status")
  }

  // ORIGIN-10
  @Test
  fun `a request without an Origin header reaches the chain`() {
    val outcome = run(MockHttpServletRequest("GET", "/health"))

    assertPassed(outcome, "no Origin")
  }

  // ORIGIN-11
  @Test
  fun `the literal null origin reaches the chain`() {
    assertPassed(send("null"), "Origin: null")
  }

  // ORIGIN-12
  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "https://wallet.example",
        "http://localhost:3000",
        "https://sub.wallet-1.example.com:8443",
        "http://[::1]:3000",
        "chrome-extension://abcdefghijklmnop",
      ]
  )
  fun `an ordinary origin reaches the chain`(origin: String) {
    assertPassed(send(origin), origin)
  }

  // ORIGIN-12 (header names are case-insensitive)
  @Test
  fun `the header name is matched case-insensitively`() {
    assertRefused(send("//@#", name = "origin"), "origin (lower case)")
    assertRefused(send("//@#", name = "ORIGIN"), "ORIGIN (upper case)")
  }

  // ORIGIN-13 and ORIGIN-01: both sides of the 300-character limit
  @Test
  fun `a valid origin of exactly 300 characters reaches the chain`() {
    val origin = "https://" + "a".repeat(300 - "https://".length)
    assertEquals(300, origin.length)

    assertPassed(send(origin), "300 characters")
  }

  // ORIGIN-01
  @Test
  fun `a valid origin of 301 characters is refused`() {
    val origin = "https://" + "a".repeat(301 - "https://".length)
    assertEquals(301, origin.length)

    assertRefused(send(origin), "301 characters")
  }

  // ORIGIN-01
  @Test
  fun `an origin of 8000 plain letters is refused`() {
    assertRefused(send("https://" + "a".repeat(8000)), "8,008 characters")
  }

  // ORIGIN-02
  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "//wallet.example",
        "https://user@wallet.example",
        "https://user:pw@wallet.example",
        "https://wallet.example/",
        "https://wallet.example/path",
        "https://wallet.example?x=1",
        "https://wallet.example#frag",
        "https://wallet.example:",
        "https://wallet.example:123456",
        "https://wallet.example:80a",
        "https://wallet.exämple",
        "https://wallet .example",
        " https://wallet.example",
        "https://wallet.example ",
        "https://wallet.example\n",
        "https://",
        "wallet.example",
        "://wallet.example",
        "NULL",
        "nullx",
        "null ",
        "null://",
        "xnull",
        "http://[::1#]",
        "http://[::1@x]",
        "http://[::1/x]",
        "http://[]",
        "http://[zz]",
        "",
        "//@#",
      ]
  )
  fun `an origin that is not null or a plain scheme host and port is refused`(origin: String) {
    assertRefused(send(origin), "Origin: '$origin'")
  }

  // ORIGIN-02: every method, including the preflight
  @Test
  fun `a preflight with an invalid origin is refused`() {
    assertRefused(send("//@#", method = "OPTIONS"), "OPTIONS")
  }

  // ORIGIN-03
  @Test
  fun `the reporter's 8004-character origin is refused in under one second`() {
    val origin = attackOrigin(8000)
    assertEquals(8004, origin.length)

    val started = System.nanoTime()
    val outcome = send(origin)
    val millis = (System.nanoTime() - started) / 1_000_000

    assertRefused(outcome, "the report's value")
    assertTrue(millis < 1000) { "took $millis ms" }
  }

  // ORIGIN-04
  @Test
  fun `a request with several Origin values is refused when any one is invalid`() {
    assertRefused(send("https://wallet.example", "//@#"), "valid first, invalid second")
    assertRefused(send("//@#", "https://wallet.example"), "invalid first, valid second")
  }

  // ORIGIN-04 (the accepted side of the same rule)
  @Test
  fun `a request with several valid Origin values reaches the chain`() {
    assertPassed(send("https://a.example", "null"), "two valid values")
  }

  // ORIGIN-05
  @Test
  fun `a refusal does not echo the header value`() {
    val origin = "//" + "@".repeat(50) + "#secret-marker"

    val outcome = send(origin)

    assertRefused(outcome, "echo check")
    assertFalse(outcome.response.contentAsString.contains("secret-marker"))
    assertNull(outcome.response.getHeader("Access-Control-Allow-Origin"))
  }

  // ORIGIN-05
  @Test
  fun `a refusal logs the method, the URI and the header length but not the value`() {
    val logged = mutableListOf<String>()
    mockkStatic(Log::class)
    try {
      every { Log.warnF(any(), *anyVararg()) } answers
        {
          val args = (invocation.args[1] as Array<*>).joinToString(" ")
          logged += "${invocation.args[0]} | $args"
        }
      every { Log.warn(any<String>()) } just runs

      val origin = "//" + "@".repeat(50) + "#secret-marker"
      send(origin, method = "OPTIONS")

      assertEquals(1, logged.size, "one warn per refusal: $logged")
      assertTrue(logged[0].contains("OPTIONS")) { logged[0] }
      assertTrue(logged[0].contains("/.well-known/stellar.toml")) { logged[0] }
      assertTrue(logged[0].contains(origin.length.toString())) { logged[0] }
      assertFalse(logged[0].contains("secret-marker")) { logged[0] }
      assertFalse(logged[0].contains("@@@")) { logged[0] }
    } finally {
      unmockkStatic(Log::class)
    }
  }
}
