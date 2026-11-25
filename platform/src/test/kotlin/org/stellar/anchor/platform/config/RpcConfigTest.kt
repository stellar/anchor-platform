package org.stellar.anchor.platform.config

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class RpcConfigTest {
  private fun resource(path: String): String =
    javaClass.classLoader.getResourceAsStream(path)!!.use { it.readBytes().decodeToString() }

  @Test
  fun `a late quote payment is held unless the operator opts in`() {
    assertFalse(RpcConfig().isHonorLateQuotePayments)
  }

  @Test
  fun `the shipped default is to hold`() {
    val defaults =
      Yaml().load<Map<String, Any>>(resource("config/anchor-config-default-values.yaml"))
    @Suppress("UNCHECKED_CAST") val rpc = defaults["rpc"] as Map<String, Any>

    assertEquals(false, rpc["honor_late_quote_payments"])
  }

  @Test
  fun `the setting is a known key in the config schema`() {
    val keys = resource("config/anchor-config-schema-v1.yaml").lines().map { it.trim() }

    assertTrue(keys.contains("rpc.honor_late_quote_payments:"))
  }
}
