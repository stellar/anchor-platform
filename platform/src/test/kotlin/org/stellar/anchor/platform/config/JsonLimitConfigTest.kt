package org.stellar.anchor.platform.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
