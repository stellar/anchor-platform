package org.stellar.anchor.platform.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class JsonLimitConfigTest {
  private fun resource(name: String): String =
    javaClass.classLoader.getResource("config/$name")!!.readText()

  private val defaults: JsonNode =
    ObjectMapper(YAMLFactory()).readTree(resource("anchor-config-default-values.yaml"))

  @Test
  fun `platform_server max_json_nodes defaults to 50000`() {
    assertEquals(50000, defaults["platform_server"]["max_json_nodes"].asInt())
  }

  @Test
  fun `platform_server max_json_depth defaults to 32`() {
    assertEquals(32, defaults["platform_server"]["max_json_depth"].asInt())
  }

  @Test
  fun `rpc max_json_nodes defaults to 10000`() {
    assertEquals(10000, defaults["rpc"]["max_json_nodes"].asInt())
  }

  /** The keys as the config manager hands them to Spring: flat, dotted, snake_case. */
  private fun flatten(prefix: String, node: JsonNode, out: MutableMap<String, Any>) {
    if (node.isObject) {
      node.fields().forEach { (name, child) -> flatten("$prefix.$name", child, out) }
    } else {
      out[prefix] = node.asText()
    }
  }

  private fun environmentFromDefaults(): StandardEnvironment {
    val flat = mutableMapOf<String, Any>()
    listOf("platform_server", "rpc").forEach { flatten(it, defaults[it], flat) }
    return StandardEnvironment().apply {
      propertySources.addFirst(MapPropertySource("shipped-defaults", flat))
    }
  }

  @Test
  fun `the shipped platform_server limits bind into PlatformServerConfig`() {
    val config = PlatformServerConfig(mockk())
    Binder.get(environmentFromDefaults()).bind("platform-server", Bindable.ofInstance(config))

    assertEquals(50000, config.maxJsonNodes)
    assertEquals(32, config.maxJsonDepth)
    assertEquals(1000, config.maxPatchRecords)
  }

  @Test
  fun `the shipped rpc limits bind into RpcConfig`() {
    val config = RpcConfig()
    Binder.get(environmentFromDefaults()).bind("rpc", Bindable.ofInstance(config))

    assertEquals(10000, config.maxJsonNodes)
    assertEquals(40, config.batchSizeLimit)
  }

  @Test
  fun `the schema lists the three JSON limit keys`() {
    val keys = resource("anchor-config-schema-v1.yaml").lines().map { it.trim() }.toSet()
    listOf(
        "platform_server.max_json_depth:",
        "platform_server.max_json_nodes:",
        "rpc.max_json_nodes:",
      )
      .forEach { assertTrue(keys.contains(it), "schema is missing $it") }
  }
}
