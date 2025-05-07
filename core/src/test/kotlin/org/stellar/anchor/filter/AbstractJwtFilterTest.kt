package org.stellar.anchor.filter

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.hc.core5.http.HttpStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.config.CustodySecretConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.setupMock

class AbstractJwtFilterTest {
  private lateinit var jwtService: JwtService

  @BeforeEach
  fun setup() {
    val secretConfig = mockk<SecretConfig>(relaxed = true)
    val custodySecretConfig = mockk<CustodySecretConfig>(relaxed = true)
    secretConfig.setupMock()
    this.jwtService = JwtService(secretConfig, custodySecretConfig)
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
}
