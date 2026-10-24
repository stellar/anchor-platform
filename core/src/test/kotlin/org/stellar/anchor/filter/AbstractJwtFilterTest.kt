package org.stellar.anchor.filter

import com.google.gson.JsonParser
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.PrintWriter
import java.io.StringWriter
import org.apache.hc.core5.http.HttpStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.setupMock

class AbstractJwtFilterTest {
  private lateinit var jwtService: JwtService

  @BeforeEach
  fun setup() {
    val secretConfig = mockk<SecretConfig>(relaxed = true)
    secretConfig.setupMock()
    this.jwtService = JwtService(secretConfig)
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun `make sure FORBIDDEN is returned when the filter requires header names other than Authorization`(
    method: String
  ) {
    val request = mockk<HttpServletRequest>(relaxed = true)
    val response = mockk<HttpServletResponse>(relaxed = true)
    val filterChain = mockk<FilterChain>(relaxed = true)

    every { request.method } returns method
    every { request.getHeader("Authorization") } returns "Authorization_Header_Value"
    val filter =
      object : AbstractJwtFilter(jwtService, "Authorization-custom") {
        @Throws(Exception::class)
        override fun check(
          jwtCipher: String?,
          request: HttpServletRequest,
          servletResponse: ServletResponse?,
        ) {}
      }

    filter.doFilter(request, response, filterChain)
    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = WebAuthJwtFilter.APPLICATION_JSON_VALUE
    }
    verify { filterChain wasNot Called }
  }

  private class Refusal(val response: HttpServletResponse, val body: StringWriter)

  private fun refusalOf(
    authorization: String?,
    chain: FilterChain = mockk(relaxed = true),
    origin: String? = null,
    check: (String?) -> Unit = {},
  ): Refusal {
    val request = mockk<HttpServletRequest>(relaxed = true)
    val response = mockk<HttpServletResponse>(relaxed = true)
    val body = StringWriter()
    every { request.method } returns "GET"
    every { request.getHeader("Authorization") } returns authorization
    every { request.getHeader("Origin") } returns origin
    every { response.writer } returns PrintWriter(body)
    val filter =
      object : AbstractJwtFilter(jwtService, "Authorization") {
        @Throws(Exception::class)
        override fun check(
          jwtCipher: String?,
          request: HttpServletRequest,
          servletResponse: ServletResponse?,
        ) = check(jwtCipher)
      }
    filter.doFilter(request, response, chain)
    return Refusal(response, body)
  }

  // SEP1CR-07
  @ParameterizedTest
  @ValueSource(strings = ["", "Basic abc", "Bearer"])
  fun `test every refusal of the JWT filter allows any origin to read the 403`(
    authorization: String
  ) {
    val refusal = refusalOf(authorization.ifEmpty { null })

    verify(exactly = 1) { refusal.response.setStatus(HttpStatus.SC_FORBIDDEN) }
    verify(exactly = 1) { refusal.response.contentType = WebAuthJwtFilter.APPLICATION_JSON_VALUE }
    verify(exactly = 1) { refusal.response.setHeader("Access-Control-Allow-Origin", "*") }
    assertEquals(
      JsonParser.parseString("""{"error":"forbidden"}"""),
      JsonParser.parseString(refusal.body.toString()),
    )
  }

  // SEP1CR-07
  @Test
  fun `test a token the check refuses is a 403 that allows any origin to read it`() {
    val refusal = refusalOf("Bearer not-a-valid-token") { throw Exception("invalid") }

    verify(exactly = 1) { refusal.response.setStatus(HttpStatus.SC_FORBIDDEN) }
    verify(exactly = 1) { refusal.response.setHeader("Access-Control-Allow-Origin", "*") }
  }

  // SEP1CR-07: the filter adds nothing to a request it lets through
  @Test
  fun `test a request the filter accepts gets no CORS header from the filter`() {
    val chain = mockk<FilterChain>(relaxed = true)
    val refusal = refusalOf("Bearer valid", chain)

    verify(exactly = 1) { chain.doFilter(any(), any()) }
    verify(exactly = 0) { refusal.response.setHeader(any(), any()) }
    verify(exactly = 0) { refusal.response.setStatus(any()) }
  }

  // SEP1CR-07 (spec A15): the header is sent whether or not the request names an Origin
  @Test
  fun `test the 403 allows any origin with or without an Origin request header`() {
    listOf(null, "", "null", "https://wallet.example").forEach { origin ->
      val refusal = refusalOf(null, origin = origin)

      verify(exactly = 1) { refusal.response.setHeader("Access-Control-Allow-Origin", "*") }
    }
  }
}
