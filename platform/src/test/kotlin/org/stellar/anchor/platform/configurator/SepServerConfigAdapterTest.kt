package org.stellar.anchor.platform.configurator

import java.util.Properties
import org.apache.catalina.valves.RemoteIpValve
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.autoconfigure.web.embedded.TomcatWebServerFactoryCustomizer
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.platform.config.SepServerConfig
import org.stellar.anchor.platform.configurator.ConfigMap.ConfigSource.ENV

class SepServerConfigAdapterTest {
  private val adapter = SepServerConfigAdapter()

  private fun configOf(vararg entries: Pair<String, String>) =
    ConfigMap().apply { entries.forEach { (key, value) -> put(key, value, ENV) } }

  private fun bound(vararg entries: Pair<String, String>): SepServerConfig {
    val env = StandardEnvironment()
    val props = Properties().apply { entries.forEach { (key, value) -> setProperty(key, value) } }
    env.propertySources.addFirst(PropertiesPropertySource("test", props))
    return Binder.get(env).bind("sep-server", SepServerConfig::class.java).orElse(SepServerConfig())
  }

  @Test
  fun `the shipped default trusts no proxy`() {
    val defaults = ConfigHelper.loadDefaultConfig()

    assertEquals(
      listOf<String>(),
      SepServerConfigAdapter.trustedProxyEntries(defaults).filter { it.isNotBlank() }
    )
    adapter.validate(defaults)
  }

  @Test
  fun `the bound default is an empty list`() {
    assertEquals(listOf<String>(), bound().trustedProxies)
    assertEquals(
      listOf<String>(),
      bound("sep_server.trusted_proxies" to "").trustedProxies.filter { it.isNotBlank() }
    )
  }

  @Test
  fun `addresses and ranges are accepted as a comma-separated value`() {
    adapter.validate(
      configOf("sep_server.trusted_proxies" to "10.0.0.0/8, 198.51.100.7,2001:db8::/32")
    )
  }

  @Test
  fun `addresses and ranges are accepted as an indexed list`() {
    adapter.validate(
      configOf(
        "sep_server.trusted_proxies[0]" to "10.0.0.0/8",
        "sep_server.trusted_proxies[1]" to "2001:db8::/32",
      )
    )
  }

  @Test
  fun `the binder reads both spellings of the key`() {
    val csv = bound("sep_server.trusted_proxies" to "10.0.0.0/8,2001:db8::/32")
    assertEquals(listOf("10.0.0.0/8", "2001:db8::/32"), csv.trustedProxies)

    val indexed =
      bound(
        "sep_server.trusted_proxies[0]" to "10.0.0.0/8",
        "sep_server.trusted_proxies[1]" to "2001:db8::/32",
      )
    assertEquals(listOf("10.0.0.0/8", "2001:db8::/32"), indexed.trustedProxies)
  }

  @Test
  fun `an invalid entry stops startup naming the key and the entry`() {
    val csv =
      assertThrows<InvalidConfigException> {
        adapter.validate(configOf("sep_server.trusted_proxies" to "10.0.0.0/8,proxy.local"))
      }
    assertTrue(csv.message!!.contains("sep_server.trusted_proxies"), csv.message)
    assertTrue(csv.message!!.contains("[proxy.local]"), csv.message)

    val indexed =
      assertThrows<InvalidConfigException> {
        adapter.validate(
          configOf(
            "sep_server.trusted_proxies[0]" to "10.0.0.0/8",
            "sep_server.trusted_proxies[1]" to "10.0.0.0/33",
          )
        )
      }
    assertTrue(indexed.message!!.contains("[10.0.0.0/33]"), indexed.message)
  }

  private fun remoteIpValveOnKubernetes(springProps: Properties): Boolean {
    val env = StandardEnvironment()
    env.propertySources.replace(
      StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
      SystemEnvironmentPropertySource(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        mapOf<String, Any>(
          "KUBERNETES_SERVICE_HOST" to "10.43.0.1",
          "KUBERNETES_SERVICE_PORT" to "443",
        ),
      ),
    )
    env.propertySources.addFirst(PropertiesPropertySource("sep", springProps))
    val serverProperties =
      Binder.get(env).bind("server", ServerProperties::class.java).orElse(ServerProperties())
    val factory = TomcatServletWebServerFactory()
    TomcatWebServerFactoryCustomizer(env, serverProperties).customize(factory)
    return factory.engineValves.any { it is RemoteIpValve }
  }

  @Test
  fun `the SEP server turns off Spring's forwarded-header handling`() {
    adapter.updateSpringEnv(ConfigHelper.loadDefaultConfig())

    assertEquals("none", adapter.props.getProperty("server.forward-headers-strategy"))
  }

  @Test
  fun `on Kubernetes no RemoteIpValve rewrites the peer address before the resolver`() {
    assertTrue(remoteIpValveOnKubernetes(Properties()))

    adapter.updateSpringEnv(ConfigHelper.loadDefaultConfig())

    assertFalse(remoteIpValveOnKubernetes(adapter.props))
  }
}
