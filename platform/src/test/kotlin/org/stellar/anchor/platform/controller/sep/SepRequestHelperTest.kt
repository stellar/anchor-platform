package org.stellar.anchor.platform.controller.sep

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.stellar.anchor.platform.config.AppLoggingConfig
import org.stellar.anchor.platform.utils.ClientIpResolver
import org.stellar.anchor.platform.utils.RequestLoggerFilter
import org.stellar.anchor.platform.utils.TrustedProxies

class SepRequestHelperTest {
  /** The client IP a controller sees after the request went through the filter. */
  private fun seenByController(
    loggerEnabled: Boolean,
    trustedProxies: List<String>,
    peer: String,
    forwardedFor: String
  ): String? {
    val config = mockk<AppLoggingConfig>(relaxed = true)
    every { config.isRequestLoggerEnabled } returns loggerEnabled
    val filter = RequestLoggerFilter(config, ClientIpResolver(TrustedProxies.parse(trustedProxies)))
    val request =
      MockHttpServletRequest().apply {
        remoteAddr = peer
        addHeader("X-Forwarded-For", forwardedFor)
      }
    var seen: String? = "controller was not reached"
    val chain = FilterChain { req, _ ->
      seen = SepRequestHelper.getClientIpAddress(req as HttpServletRequest)
    }
    filter.doFilter(request, MockHttpServletResponse(), chain)
    return seen
  }

  @Test
  fun `with the request logger disabled the controller still gets the resolved address`() {
    val seen = seenByController(false, listOf(), "198.51.100.99", "203.0.113.9")
    assertEquals("198.51.100.99", seen)
  }

  @Test
  fun `with the request logger enabled the controller gets the resolved address, not the header`() {
    val seen = seenByController(true, listOf(), "198.51.100.99", "203.0.113.9")
    assertEquals("198.51.100.99", seen)
  }

  @Test
  fun `behind a trusted proxy the controller gets the client address with the logger off or on`() {
    val trusted = listOf("198.51.100.7", "10.0.0.0/8")
    assertEquals(
      "203.0.113.9",
      seenByController(false, trusted, "198.51.100.7", "6.6.6.6, 203.0.113.9, 10.0.0.1")
    )
    assertEquals(
      "203.0.113.9",
      seenByController(true, trusted, "198.51.100.7", "6.6.6.6, 203.0.113.9, 10.0.0.1")
    )
  }
}
