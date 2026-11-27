package org.stellar.anchor.platform.component.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.core.Ordered
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.stellar.anchor.platform.EventProcessingServer
import org.stellar.anchor.platform.PlatformServer
import org.stellar.anchor.platform.SepServer
import org.stellar.anchor.platform.StellarObservingServer
import org.stellar.anchor.platform.component.management.OriginHeaderManagementConfiguration
import org.stellar.anchor.platform.utils.OriginHeaderFilter

/**
 * The `Origin` guard must sit in front of every server's `@CrossOrigin` controllers, ahead of
 * Spring MVC and of the token filters, and be registered from the package all four servers scan.
 */
class OriginHeaderBeansTest {
  private val runner =
    ApplicationContextRunner().withUserConfiguration(OriginHeaderBeans::class.java)

  // ORIGIN-09
  @Test
  fun `the filter is registered for every path at the highest precedence`() {
    runner.run { context ->
      @Suppress("UNCHECKED_CAST")
      val registration =
        context.getBean("originHeaderFilter") as FilterRegistrationBean<jakarta.servlet.Filter>

      assertTrue(registration.filter is OriginHeaderFilter)
      assertEquals(setOf("/*"), registration.urlPatterns.toSet())
      assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.order)
    }
  }

  // ORIGIN-09: the registered instance is the guard, not a stand-in
  @Test
  fun `the registered filter refuses an invalid origin`() {
    runner.run { context ->
      @Suppress("UNCHECKED_CAST")
      val registration =
        context.getBean("originHeaderFilter") as FilterRegistrationBean<jakarta.servlet.Filter>
      val request = MockHttpServletRequest("GET", "/auth").apply { addHeader("Origin", "//@#") }
      val response = MockHttpServletResponse()
      val chain = MockFilterChain()

      registration.filter.doFilter(request, response, chain)

      assertEquals(400, response.status)
      assertFalse(chain.request != null, "the chain was called")
    }
  }

  // ORIGIN-09: the SEP, Platform, Observer and Event processor servers all scan this package
  @Test
  fun `every server scans the package that registers the filter`() {
    val servers =
      listOf(
        SepServer::class.java,
        PlatformServer::class.java,
        StellarObservingServer::class.java,
        EventProcessingServer::class.java,
      )
    val pkg = OriginHeaderBeans::class.java.packageName

    for (server in servers) {
      val scan = server.getAnnotation(ComponentScan::class.java)
      assertNotNull(scan, "${server.simpleName} has no @ComponentScan")
      assertTrue(scan.basePackages.contains(pkg)) {
        "${server.simpleName} does not scan $pkg: ${scan.basePackages.toList()}"
      }
    }
  }

  // ORIGIN-16: Boot requires management configurations to stay out of the component scan; a scan
  // would register the actuator filter in the main context too (duplicate bean name)
  @Test
  fun `no server scans the package of the management registration`() {
    val servers =
      listOf(
        SepServer::class.java,
        PlatformServer::class.java,
        StellarObservingServer::class.java,
        EventProcessingServer::class.java,
      )
    val pkg = OriginHeaderManagementConfiguration::class.java.packageName

    for (server in servers) {
      val scan = server.getAnnotation(ComponentScan::class.java)
      assertNotNull(scan, "${server.simpleName} has no @ComponentScan")
      for (scanned in scan.basePackages) {
        assertFalse(pkg == scanned || pkg.startsWith("$scanned.")) {
          "${server.simpleName} scans $scanned, which contains $pkg"
        }
      }
    }
  }
}
