package org.stellar.anchor.platform.component.platform

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.mockk
import java.lang.reflect.Type
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.gson.GsonAutoConfiguration
import org.springframework.boot.autoconfigure.http.HttpMessageConverters
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.converter.json.GsonHttpMessageConverter
import org.springframework.mock.http.MockHttpInputMessage
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.api.platform.PatchTransactionsRequest
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.platform.config.PlatformServerConfig
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.utils.BoundedGsonHttpMessageConverter
import org.stellar.anchor.platform.utils.JsonArrayLimitException
import org.stellar.anchor.platform.utils.JsonStructureLimitException
import org.stellar.anchor.util.GsonUtils

class PlatformServerBeansConverterTest {
  private val gson: Gson = GsonUtils.getInstance()
  private val beans = PlatformServerBeans()

  private fun platformConfig(
    nodes: Int = 50_000,
    depth: Int = 32,
    patchRecords: Int = 1000,
  ): PlatformServerConfig =
    PlatformServerConfig(mockk()).apply {
      maxJsonNodes = nodes
      maxJsonDepth = depth
      maxPatchRecords = patchRecords
    }

  private fun rpcConfig(nodes: Int = 10_000, batch: Int = 40): RpcConfig =
    RpcConfig().apply {
      maxJsonNodes = nodes
      batchSizeLimit = batch
    }

  private fun converter(platform: PlatformServerConfig, rpc: RpcConfig) =
    beans.boundedGsonHttpMessageConverter(gson, platform, rpc)

  private fun read(c: GsonHttpMessageConverter, type: Type, body: String): Any? {
    val message = MockHttpInputMessage(body.toByteArray())
    message.headers.contentType = MediaType.APPLICATION_JSON
    return c.read(type, null, message)
  }

  private fun causeOf(c: GsonHttpMessageConverter, type: Type, body: String): Throwable =
    assertThrows(HttpMessageNotReadableException::class.java) { read(c, type, body) }.cause!!

  private val rpcType: Type =
    TypeToken.getParameterized(MutableList::class.java, RpcRequest::class.java).type
  private val mapType: Type = object : TypeToken<Map<String, Any>>() {}.type

  @Test
  fun `the shipped defaults are accepted`() {
    assertInstanceOf(
      BoundedGsonHttpMessageConverter::class.java,
      converter(platformConfig(), rpcConfig()),
    )
  }

  @Test
  fun `the smallest valid value of each limit is accepted`() {
    assertInstanceOf(
      BoundedGsonHttpMessageConverter::class.java,
      converter(platformConfig(nodes = 1), rpcConfig()),
    )
    assertInstanceOf(
      BoundedGsonHttpMessageConverter::class.java,
      converter(platformConfig(depth = 1), rpcConfig()),
    )
    assertInstanceOf(
      BoundedGsonHttpMessageConverter::class.java,
      converter(platformConfig(), rpcConfig(nodes = 1)),
    )
  }

  @Test
  fun `zero and negative platform_server max_json_nodes fail startup naming the key`() {
    listOf(0, -1).forEach {
      val ex =
        assertThrows(InvalidConfigException::class.java) {
          converter(platformConfig(nodes = it), rpcConfig())
        }
      assertTrue(ex.message!!.contains("platform_server.max_json_nodes"), ex.message)
    }
  }

  @Test
  fun `zero and negative platform_server max_json_depth fail startup naming the key`() {
    listOf(0, -1).forEach {
      val ex =
        assertThrows(InvalidConfigException::class.java) {
          converter(platformConfig(depth = it), rpcConfig())
        }
      assertTrue(ex.message!!.contains("platform_server.max_json_depth"), ex.message)
    }
  }

  @Test
  fun `zero and negative rpc max_json_nodes fail startup naming the key`() {
    listOf(0, -1).forEach {
      val ex =
        assertThrows(InvalidConfigException::class.java) {
          converter(platformConfig(), rpcConfig(nodes = it))
        }
      assertTrue(ex.message!!.contains("rpc.max_json_nodes"), ex.message)
    }
  }

  @Test
  fun `the configured limits reach the converter`() {
    val request = """{"id":1,"jsonrpc":"2.0","method":"m","params":{"a":1}}"""

    // rpc.batch_size_limit: two requests over a limit of one
    val batch = converter(platformConfig(), rpcConfig(batch = 1))
    val batchCause = causeOf(batch, rpcType, "[$request,$request]")
    assertNull((batchCause as JsonArrayLimitException).member)
    assertEquals(1, batchCause.limit)

    // rpc.max_json_nodes: a request is 6 values, so 5 is too few
    val rpcNodes = converter(platformConfig(), rpcConfig(nodes = 5))
    assertEquals(5, (causeOf(rpcNodes, rpcType, "[$request]") as JsonStructureLimitException).limit)

    // platform_server.max_patch_records
    val patch = converter(platformConfig(patchRecords = 1), rpcConfig())
    val patchCause = causeOf(patch, PatchTransactionsRequest::class.java, """{"records":[{},{}]}""")
    assertEquals("records", (patchCause as JsonArrayLimitException).member)
    assertEquals(1, patchCause.limit)

    // platform_server.max_json_nodes and max_json_depth for any other type
    val nodes = converter(platformConfig(nodes = 2), rpcConfig())
    assertEquals(
      2,
      (causeOf(nodes, mapType, """{"a":[1,2]}""") as JsonStructureLimitException).limit
    )
    val depth = converter(platformConfig(depth = 1), rpcConfig())
    assertEquals(
      JsonStructureLimitException.DEPTH,
      (causeOf(depth, mapType, """{"a":{"b":1}}""") as JsonStructureLimitException).limitName,
    )
  }

  @Test
  fun `the converter is registered as a bean method of the platform server`() {
    val method =
      PlatformServerBeans::class
        .java
        .getMethod(
          "boundedGsonHttpMessageConverter",
          Gson::class.java,
          PlatformServerConfig::class.java,
          RpcConfig::class.java,
        )
    assertNotNull(method.getAnnotation(Bean::class.java))
    assertEquals(GsonHttpMessageConverter::class.java, method.returnType)
  }

  @Test
  fun `spring boot uses the bounded converter instead of its default gson converter`() {
    ApplicationContextRunner()
      .withConfiguration(
        AutoConfigurations.of(
          GsonAutoConfiguration::class.java,
          HttpMessageConvertersAutoConfiguration::class.java,
        )
      )
      .withPropertyValues("spring.mvc.converters.preferred-json-mapper=gson")
      .withBean(Gson::class.java, { gson })
      .withBean(
        GsonHttpMessageConverter::class.java,
        { converter(platformConfig(), rpcConfig()) },
      )
      .run { context ->
        val gsonConverters =
          context
            .getBean(HttpMessageConverters::class.java)
            .converters
            .filterIsInstance<GsonHttpMessageConverter>()
        assertTrue(gsonConverters.isNotEmpty())
        assertTrue(
          gsonConverters.all { it is BoundedGsonHttpMessageConverter },
          "a plain GsonHttpMessageConverter is still registered: $gsonConverters",
        )
        // and it is the first converter able to read JSON, ahead of any Jackson converter
        val firstJson =
          context.getBean(HttpMessageConverters::class.java).converters.first {
            it.canRead(Map::class.java, MediaType.APPLICATION_JSON)
          }
        assertInstanceOf(BoundedGsonHttpMessageConverter::class.java, firstJson)
      }
  }
}
