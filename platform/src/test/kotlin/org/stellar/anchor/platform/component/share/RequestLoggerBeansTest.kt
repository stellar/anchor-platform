package org.stellar.anchor.platform.component.share

import java.util.function.Supplier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.stellar.anchor.platform.config.AppLoggingConfig
import org.stellar.anchor.platform.config.SepServerConfig
import org.stellar.anchor.platform.utils.ClientIpResolver
import org.stellar.anchor.platform.utils.RequestLoggerFilter.ATTRIBUTE_CLIENT_IP_ADDRESS

/** `sep_server.trusted_proxies` must reach both the resolver and the registered filter. */
class RequestLoggerBeansTest {
  private fun runner(trustedProxies: List<String>) =
    ApplicationContextRunner()
      .withUserConfiguration(RequestLoggerBeans::class.java)
      .withBean(AppLoggingConfig::class.java, Supplier { AppLoggingConfig() })
      .withBean(
        SepServerConfig::class.java,
        Supplier { SepServerConfig().apply { this.trustedProxies = trustedProxies } }
      )

  private fun behindProxy() =
    MockHttpServletRequest().apply {
      remoteAddr = "198.51.100.7"
      addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9, 10.0.0.1")
    }

  @Test
  fun `the resolver bean honors the configured trusted proxies`() {
    runner(listOf("198.51.100.7", "10.0.0.0/8")).run { context ->
      assertEquals(
        "203.0.113.9",
        context.getBean(ClientIpResolver::class.java).resolve(behindProxy())
      )
    }
  }

  @Test
  fun `the resolver bean trusts nobody when none are configured`() {
    runner(listOf()).run { context ->
      assertEquals(
        "198.51.100.7",
        context.getBean(ClientIpResolver::class.java).resolve(behindProxy())
      )
    }
  }

  @Test
  fun `the registered filter honors the configured trusted proxies`() {
    runner(listOf("198.51.100.7", "10.0.0.0/8")).run { context ->
      @Suppress("UNCHECKED_CAST")
      val registration =
        context.getBean("requestLoggerFilter") as FilterRegistrationBean<jakarta.servlet.Filter>
      val request = behindProxy()

      registration.filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

      assertEquals("203.0.113.9", request.getAttribute(ATTRIBUTE_CLIENT_IP_ADDRESS))
    }
  }
}
