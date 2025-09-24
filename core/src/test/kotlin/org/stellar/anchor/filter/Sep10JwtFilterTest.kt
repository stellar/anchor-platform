package org.stellar.anchor.filter

import io.mockk.*
import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.apache.http.HttpStatus
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.TestHelper.Companion.createSep10Jwt
import org.stellar.anchor.auth.AbstractJwt
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.config.CustodySecretConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.filter.Sep10JwtFilter.APPLICATION_JSON_VALUE
import org.stellar.anchor.filter.Sep10JwtFilter.JWT_TOKEN
import org.stellar.anchor.setupMock

@Order(85)
internal class Sep10JwtFilterTest {
  companion object {
    private const val PUBLIC_KEY = "GBJDSMTMG4YBP27ZILV665XBISBBNRP62YB7WZA2IQX2HIPK7ABLF4C2"
  }

  private lateinit var appConfig: AppConfig
  private lateinit var secretConfig: SecretConfig
  private lateinit var custodySecretConfig: CustodySecretConfig
  private lateinit var jwtService: JwtService
  private lateinit var sep10TokenFilter: Sep10JwtFilter
  private lateinit var request: HttpServletRequest
  private lateinit var response: HttpServletResponse
  private lateinit var mockFilterChain: FilterChain

  @BeforeEach
  fun setup() {
    this.appConfig = mockk(relaxed = true)
    this.secretConfig = mockk(relaxed = true)
    this.custodySecretConfig = mockk(relaxed = true)
    secretConfig.setupMock()
    this.jwtService = JwtService(secretConfig, custodySecretConfig)
    this.sep10TokenFilter = Sep10JwtFilter(jwtService)
    this.request = mockk(relaxed = true)
    this.response = mockk(relaxed = true)
    this.mockFilterChain = mockk(relaxed = true)
  }

  @Test
  fun `make sure bad servlet throws exception`() {
    val mockServletRequest = mockk<ServletRequest>(relaxed = true)
    val mockServletResponse = mockk<ServletResponse>(relaxed = true)

    assertThrows<ServletException> {
      sep10TokenFilter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)
    }

    assertThrows<ServletException> {
      sep10TokenFilter.doFilter(request, mockServletResponse, mockFilterChain)
    }
  }

  @Test
  fun `test OPTIONS method works fine without auth header`() {
    every { request.method } returns "OPTIONS"

    sep10TokenFilter.doFilter(request, response, mockFilterChain)

    verify { mockFilterChain.doFilter(request, response) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun `make sure FORBIDDEN is returned when no token exists`(method: String) {
    every { request.method } returns method
    every { request.getHeader("Authorization") } returns null

    sep10TokenFilter.doFilter(request, response, mockFilterChain)
    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
    verify { mockFilterChain wasNot Called }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun `make sure FORBIDDEN is returned when encounter an empty token`(method: String) {
    every { request.method } returns method
    every { request.getHeader("Authorization") } returns ""

    sep10TokenFilter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun `make sure Bearer123 does not cause confusion and return FORBIDDEN`(method: String) {
    every { request.method } returns method
    every { request.getHeader("Authorization") } returns "Bearer123"

    sep10TokenFilter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun `make sure check() exception returns FORBIDDEN and does not cause 500`(method: String) {
    every { request.method } returns method
    val mockFilter = spyk(sep10TokenFilter)
    every { mockFilter.check(any(), any(), any()) } answers { throw Exception("Not validate") }

    mockFilter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun `make sure FORBIDDEN is returned when null token is decoded and does not cause 500 `(
    method: String
  ) {
    every { request.method } returns method
    val mockJwtService = spyk(jwtService)
    every { mockJwtService.decode(any(), AbstractJwt::class.java) } returns null
    val filter = Sep10JwtFilter(mockJwtService)

    filter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun `make sure a valid token returns OK`(method: String) {
    every { request.method } returns method
    val slot = slot<Sep10Jwt>()
    every { request.setAttribute(JWT_TOKEN, capture(slot)) } answers {}

    val jwtToken = jwtService.encode(createSep10Jwt(PUBLIC_KEY, null, "stellar.org"))
    every { request.getHeader("Authorization") } returns "Bearer $jwtToken"
    sep10TokenFilter.doFilter(request, response, mockFilterChain)

    verify { mockFilterChain.doFilter(request, response) }
    verify(exactly = 1) { request.setAttribute(JWT_TOKEN, any()) }
    assertEquals(jwtToken, jwtService.encode(slot.captured))
  }
}
