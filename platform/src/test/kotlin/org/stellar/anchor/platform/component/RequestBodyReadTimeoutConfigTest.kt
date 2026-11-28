package org.stellar.anchor.platform.component

import java.time.Duration
import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.StandardEnvironment
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.platform.component.platform.PlatformServerBeans
import org.stellar.anchor.platform.component.sep.SepBeans
import org.stellar.anchor.platform.config.PlatformServerConfig
import org.stellar.anchor.platform.config.SepServerConfig
import org.stellar.anchor.platform.configurator.ConfigHelper
import org.stellar.anchor.platform.utils.RequestBodyReadDeadlineValve

class RequestBodyReadTimeoutConfigTest {
  private val sepKey = "sep_server.request_body_read_timeout_seconds"
  private val platformKey = "platform_server.request_body_read_timeout_seconds"

  private fun sepConfig(seconds: Long) =
    SepServerConfig().apply { requestBodyReadTimeoutSeconds = seconds }

  private fun platformConfig(seconds: Long) =
    PlatformServerConfig(null).apply { requestBodyReadTimeoutSeconds = seconds }

  private fun valveOf(factory: TomcatServletWebServerFactory) =
    factory.contextValves.filterIsInstance<RequestBodyReadDeadlineValve>().single()

  @Test
  fun `the shipped defaults give both servers a 30 second deadline`() {
    val defaults = ConfigHelper.loadDefaultConfig()

    assertEquals("30", defaults.get(sepKey).value)
    assertEquals("30", defaults.get(platformKey).value)
  }

  @Test
  fun `the SEP key set in the configuration reaches the SEP server config`() {
    val env = StandardEnvironment()
    val props = Properties().apply { setProperty(sepKey, "45") }
    env.propertySources.addFirst(PropertiesPropertySource("test", props))

    val bound = Binder.get(env).bind("sep-server", SepServerConfig::class.java).get()

    assertEquals(45, bound.requestBodyReadTimeoutSeconds)
  }

  @Test
  fun `the Platform key set in the configuration reaches the Platform server config`() {
    val env = StandardEnvironment()
    val props = Properties().apply { setProperty(platformKey, "45") }
    env.propertySources.addFirst(PropertiesPropertySource("test", props))

    val bound =
      Binder.get(env).bind("platform-server", Bindable.ofInstance(platformConfig(0))).get()

    assertEquals(45, bound.requestBodyReadTimeoutSeconds)
  }

  @Test
  fun `the SEP server refuses a zero or negative deadline naming the key`() {
    for (bad in listOf(0L, -1L)) {
      val ex =
        assertThrows<InvalidConfigException> {
          SepBeans().requestBodyReadDeadlineCustomizer(sepConfig(bad))
        }
      assertTrue(ex.message!!.contains(sepKey), ex.message)
    }
  }

  @Test
  fun `the Platform server refuses a zero or negative deadline naming the key`() {
    for (bad in listOf(0L, -1L)) {
      val ex =
        assertThrows<InvalidConfigException> {
          PlatformServerBeans().requestBodyReadDeadlineCustomizer(platformConfig(bad))
        }
      assertTrue(ex.message!!.contains(platformKey), ex.message)
    }
  }

  @Test
  fun `the SEP customizer adds the valve with the configured deadline`() {
    val factory = TomcatServletWebServerFactory()

    SepBeans().requestBodyReadDeadlineCustomizer(sepConfig(1)).customize(factory)

    assertEquals(Duration.ofSeconds(1), valveOf(factory).timeout)
  }

  @Test
  fun `the Platform customizer adds the valve with the configured deadline`() {
    val factory = TomcatServletWebServerFactory()

    PlatformServerBeans().requestBodyReadDeadlineCustomizer(platformConfig(45)).customize(factory)

    assertEquals(Duration.ofSeconds(45), valveOf(factory).timeout)
  }
}
