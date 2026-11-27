package org.stellar.anchor.platform.component.sep

import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.Ordered
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.StandardEnvironment
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.platform.config.SepServerConfig
import org.stellar.anchor.platform.configurator.ConfigHelper

/** HackerOne #4233342 (ANCHOR-1567): configuration and registration of the form body limit. */
class SepBeansFormLimitTest {
  private fun config(size: Long) = SepServerConfig().apply { maxFormBodySize = size }

  // FORM-11
  @Test
  fun `the shipped default limit is 16 KiB`() {
    val defaults = ConfigHelper.loadDefaultConfig()
    assertEquals(16384, defaults.getInt("sep_server.max_form_body_size"))
  }

  // FORM-11
  @Test
  fun `the configured limit is bound from sep_server max_form_body_size`() {
    val env = StandardEnvironment()
    val props = Properties().apply { setProperty("sep_server.max_form_body_size", "2048") }
    env.propertySources.addFirst(PropertiesPropertySource("test", props))
    val bound =
      Binder.get(env).bind("sep-server", SepServerConfig::class.java).orElse(SepServerConfig())
    assertEquals(2048, bound.maxFormBodySize)
  }

  // FORM-12
  @Test
  fun `a zero or negative limit refuses to start and names the key`() {
    listOf(0L, -1L).forEach { size ->
      val e =
        assertThrows<InvalidConfigException> { SepBeans().formBodySizeLimitFilter(config(size)) }
      assertTrue(e.message!!.contains("sep_server.max_form_body_size"), e.message)
    }
  }

  // FORM-13
  @Test
  fun `the filter is registered first, on every path`() {
    val registration = SepBeans().formBodySizeLimitFilter(config(16384))
    assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.order)
    assertEquals(setOf("/*"), registration.urlPatterns.toSet())
  }
}
