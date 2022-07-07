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
import org.stellar.anchor.filter.JwtTokenFilter.APPLICATION_JSON_VALUE

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ApiKeyFilterTest {
  companion object {
    private const val API_KEY = "MY_API_KEY"
  }

  private lateinit var request: HttpServletRequest
  private lateinit var response: HttpServletResponse
  private lateinit var apiKeyFilter: ApiKeyFilter
  private lateinit var mockFilterChain: FilterChain

  @BeforeEach
  fun setup() {
    this.request = mockk(relaxed = true)
    this.response = mockk(relaxed = true)
    this.apiKeyFilter = ApiKeyFilter(API_KEY)
    this.mockFilterChain = mockk(relaxed = true)
  }

  @AfterEach
  fun teardown() {
    clearAllMocks()
    unmockkAll()
  }

  @Test
  fun testOptionsMethodDoesntNeedAuth() {
    every { request.method } returns "OPTIONS"

    apiKeyFilter.doFilter(request, response, mockFilterChain)

    verify { mockFilterChain.doFilter(request, response) }
  }

  @Test
  fun testBadServletIsNotAccepted() {
    val mockServletRequest = mockk<ServletRequest>(relaxed = true)
    val mockServletResponse = mockk<ServletResponse>(relaxed = true)

    var ex: ServletException = assertThrows {
      apiKeyFilter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)
    }
    assertEquals("the request must be a HttpServletRequest", ex.message)

    ex = assertThrows { apiKeyFilter.doFilter(request, mockServletResponse, mockFilterChain) }
    assertEquals("the request must be a HttpServletRequest", ex.message)

    ex = assertThrows { apiKeyFilter.doFilter(mockServletRequest, response, mockFilterChain) }
    assertEquals("the request must be a HttpServletRequest", ex.message)
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun testNoApiKeyReturnsForbidden(method: String) {
    every { request.method } returns method
    every { request.getHeader("X-Api-Key") } returns null

    apiKeyFilter.doFilter(request, response, mockFilterChain)
    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
    verify { mockFilterChain wasNot Called }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun testNoBearerInAuthHeaderReturnsForbidden(method: String) {
    every { request.method } returns method
    every { request.getHeader("X-Api-Key") } returns ""

    apiKeyFilter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun testMismatchingApiKeyReturnsForbidden(method: String) {
    every { request.method } returns method
    every { request.getHeader("X-Api-Key") } returns "123"

    apiKeyFilter.doFilter(request, response, mockFilterChain)

    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun testMatchingApiKeySucceeds(method: String) {
    every { request.method } returns method
    every { request.getHeader("X-Api-Key") } returns API_KEY

    apiKeyFilter.doFilter(request, response, mockFilterChain)

    verify { mockFilterChain.doFilter(request, response) }
  }
}
