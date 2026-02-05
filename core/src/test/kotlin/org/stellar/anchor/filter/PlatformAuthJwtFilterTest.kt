package org.stellar.anchor.filter

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.hc.core5.http.HttpStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.auth.JwtService

internal class PlatformAuthJwtFilterTest {
  private lateinit var jwtService: JwtService
  private lateinit var request: HttpServletRequest
  private lateinit var response: HttpServletResponse
  private lateinit var mockFilterChain: FilterChain

  @BeforeEach
  fun setup() {
    this.jwtService = mockk(relaxed = true)
    this.request = mockk(relaxed = true)
    this.response = mockk(relaxed = true)
    this.mockFilterChain = mockk(relaxed = true)
  }

  @Test
  fun `health endpoint bypasses jwt auth`() {
    every { request.method } returns "GET"
    every { request.servletPath } returns "/health"
    every { request.getHeader("Authorization") } returns null
    val filter = spyk(PlatformAuthJwtFilter(jwtService, "Authorization"))

    filter.doFilter(request, response, mockFilterChain)

    verify { mockFilterChain.doFilter(request, response) }
    verify(exactly = 0) { response.setStatus(HttpStatus.SC_FORBIDDEN) }
    verify(exactly = 0) { filter.check(any(), any(), any()) }
  }
}
