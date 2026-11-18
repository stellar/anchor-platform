package org.stellar.anchor.platform.controller.sep

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.stellar.anchor.api.sep.sep24.InteractiveTransactionResponse
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionResponse
import org.stellar.anchor.auth.WebAuthJwt
import org.stellar.anchor.filter.WebAuthJwtFilter.JWT_TOKEN
import org.stellar.anchor.platform.config.AppLoggingConfig
import org.stellar.anchor.platform.utils.ClientIpResolver
import org.stellar.anchor.platform.utils.RequestLoggerFilter
import org.stellar.anchor.platform.utils.TransactionCreationRateLimiter
import org.stellar.anchor.platform.utils.TrustedProxies
import org.stellar.anchor.sep24.Sep24Service
import org.stellar.anchor.sep31.Sep31Service

/** A value the caller writes into a request body never becomes the stored client IP. */
class ClientIpControllerTest {
  private val peer = "198.51.100.99"
  private val spoofed = "203.0.113.9"

  private val rateLimiter = mockk<TransactionCreationRateLimiter>(relaxed = true)
  private val token = mockk<WebAuthJwt>(relaxed = true)

  /**
   * Sends one request through the request filter into [controller], as the servlet container does.
   */
  private fun throughFilter(controller: (HttpServletRequest) -> Unit) {
    val config = mockk<AppLoggingConfig>(relaxed = true)
    every { config.isRequestLoggerEnabled } returns false
    val filter = RequestLoggerFilter(config, ClientIpResolver(TrustedProxies.parse(listOf())))
    val request =
      MockHttpServletRequest().apply {
        remoteAddr = peer
        addHeader("X-Forwarded-For", spoofed)
        setAttribute(JWT_TOKEN, token)
      }
    filter.doFilter(
      request,
      MockHttpServletResponse(),
      FilterChain { req, _ -> controller(req as HttpServletRequest) }
    )
  }

  @Test
  fun `SEP-31 ignores a requestClientIpAddress in the body`() {
    val service = mockk<Sep31Service>()
    val passed = slot<Sep31PostTransactionRequest>()
    every { service.postTransaction(any(), capture(passed)) } returns
      mockk<Sep31PostTransactionResponse>(relaxed = true)
    val body = Sep31PostTransactionRequest().apply { requestClientIpAddress = spoofed }

    throughFilter { Sep31Controller(service, rateLimiter).postTransaction(it, body) }

    assertEquals(peer, passed.captured.requestClientIpAddress)
  }

  @Test
  fun `SEP-24 deposit ignores a clientIpAddress in the body`() {
    val service = mockk<Sep24Service>()
    val passed = slot<Map<String, String>>()
    every { service.deposit(any(), capture(passed)) } returns
      InteractiveTransactionResponse("interactive_customer_info_needed", "https://a.b", "id")

    throughFilter {
      Sep24Controller(service, rateLimiter).deposit(it, hashMapOf("clientIpAddress" to spoofed))
    }

    assertEquals(peer, passed.captured["clientIpAddress"])
  }

  @Test
  fun `SEP-24 withdraw ignores a clientIpAddress in the body`() {
    val service = mockk<Sep24Service>()
    val passed = slot<Map<String, String>>()
    every { service.withdraw(any(), capture(passed)) } returns
      InteractiveTransactionResponse("interactive_customer_info_needed", "https://a.b", "id")

    throughFilter {
      Sep24Controller(service, rateLimiter).withdraw(it, hashMapOf("clientIpAddress" to spoofed))
    }

    assertEquals(peer, passed.captured["clientIpAddress"])
  }
}
