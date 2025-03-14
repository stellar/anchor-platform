package org.stellar.anchor.filter

import io.mockk.*
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.http.HttpStatus
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.Constants.Companion.TEST_JWT_SECRET
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.filter.BaseTokenFilter.APPLICATION_JSON_VALUE
import org.stellar.anchor.filter.BaseTokenFilter.JWT_TOKEN
import org.stellar.anchor.sep10.JwtService
import org.stellar.anchor.sep10.JwtToken

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class Sep10TokenFilterTest {
  companion object {
    private const val PUBLIC_KEY = "GBJDSMTMG4YBP27ZILV665XBISBBNRP62YB7WZA2IQX2HIPK7ABLF4C2"
  }

  lateinit var appConfig: AppConfig
  lateinit var jwtService: JwtService
  lateinit var sep10Config: Sep10Config
  lateinit var sep10TokenFilter: Sep10TokenFilter
  lateinit var request: HttpServletRequest
  lateinit var response: HttpServletResponse
  lateinit var mockFilterChain: FilterChain
  lateinit var jwtToken: String

  @BeforeEach
  fun setup() {
    this.appConfig = mockk(relaxed = true)
    every { appConfig.jwtSecretKey } returns TEST_JWT_SECRET
    this.jwtService = JwtService(appConfig)
    this.sep10Config = mockk<Sep10Config>(relaxed = true)
    this.sep10TokenFilter = Sep10TokenFilter(sep10Config, jwtService)
    this.request = mockk<HttpServletRequest>(relaxed = true)
    this.response = mockk<HttpServletResponse>(relaxed = true)
    this.mockFilterChain = mockk<FilterChain>(relaxed = true)

    every { sep10Config.enabled } returns true
    this.jwtToken = createJwtToken()
    every { request.getHeader("Authorization") } returns "Bearer $jwtToken"
  }

  @AfterEach
  fun teardown() {
    clearAllMocks()
    unmockkAll()
  }

  @Test
  fun testBadServlet() {
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
  fun testOptions() {
    every { request.method } returns "OPTIONS"

    sep10TokenFilter.doFilter(request, response, mockFilterChain)

    verify {
      mockFilterChain.doFilter(request, response)
      sep10Config wasNot Called
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun testNoTokenForbidden(method: String) {
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
  fun testNoBearer() {
    every { request.getHeader("Authorization") } returns ""

    sep10TokenFilter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun testBearerSplitError() {
    every { request.getHeader("Authorization") } returns "Bearer123"

    sep10TokenFilter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun testValidationError(method: String) {
    every { request.method } returns method
    val mockFilter = spyk(sep10TokenFilter)
    every { mockFilter.validate(any()) } answers { throw Exception("Not validate") }

    mockFilter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun testNullToken(method: String) {
    every { request.method } returns method
    val mockJwtService = spyk(jwtService)
    every { mockJwtService.decode(any()) } returns null
    val filter = Sep10TokenFilter(sep10Config, mockJwtService)

    filter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun testValidToken(method: String) {
    every { request.method } returns method
    val slot = slot<JwtToken>()
    every { request.setAttribute(JWT_TOKEN, capture(slot)) } answers {}

    sep10TokenFilter.doFilter(request, response, mockFilterChain)

    verify { mockFilterChain.doFilter(request, response) }
    verify(exactly = 1) { request.setAttribute(JWT_TOKEN, any()) }
    assertEquals(jwtToken, jwtService.encode(slot.captured))
  }

  private fun createJwtToken(): String {
    val issuedAt: Long = System.currentTimeMillis() / 1000L
    val jwtToken =
      JwtToken.of(
        appConfig.hostUrl + "/auth",
        PUBLIC_KEY,
        issuedAt,
        issuedAt + 60,
        "",
        "vibrant.stellar.org",
        null
      )
    return jwtService.encode(jwtToken)
  }
}
