package org.stellar.anchor.filter

import io.mockk.*
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.hc.core5.http.HttpStatus
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.filter.WebAuthJwtFilter.APPLICATION_JSON_VALUE

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
    this.apiKeyFilter = ApiKeyFilter(API_KEY, "X-Api-Key")
    this.mockFilterChain = mockk(relaxed = true)
  }

  @Test
  fun `test the OPTIONS Method does not need AUTH`() {
    every { request.method } returns "OPTIONS"

    apiKeyFilter.doFilter(request, response, mockFilterChain)

    verify { mockFilterChain.doFilter(request, response) }
  }

  @Test
  fun `make sure bad servlet is not accepted`() {
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
  fun `make sure FORBIDDEN is returned when no X-Api-Key is not specified`(method: String) {
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
  fun `make sure FORBIDDEN is returned when no BEARER in the auth header`(method: String) {
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
  fun `make sure FORBIDDEN is returned when having mismatching api key`(method: String) {
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
  fun `test matching api key returns OK`(method: String) {
    every { request.method } returns method
    every { request.getHeader("X-Api-Key") } returns API_KEY

    apiKeyFilter.doFilter(request, response, mockFilterChain)

    verify { mockFilterChain.doFilter(request, response) }
  }

  @Test
  fun `health endpoint bypasses api key auth`() {
    every { request.method } returns "GET"
    every { request.servletPath } returns "/health"
    every { request.getHeader("X-Api-Key") } returns null

    apiKeyFilter.doFilter(request, response, mockFilterChain)

    verify { mockFilterChain.doFilter(request, response) }
    verify(exactly = 0) { response.setStatus(HttpStatus.SC_FORBIDDEN) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["GET", "PUT", "POST", "DELETE"])
  fun `make sure FORBIDDEN is returned when the filter requires header names other than X-Api-Key`(
    method: String
  ) {
    val filterChain = mockk<FilterChain>(relaxed = true)

    every { request.method } returns method
    every { request.getHeader("X-Api-Key") } returns API_KEY
    apiKeyFilter = ApiKeyFilter(API_KEY, "X-Api-Key-custom")

    apiKeyFilter.doFilter(request, response, filterChain)
    verify(exactly = 1) {
      response.setStatus(HttpStatus.SC_FORBIDDEN)
      response.contentType = APPLICATION_JSON_VALUE
    }
    verify { filterChain wasNot Called }
  }
}
