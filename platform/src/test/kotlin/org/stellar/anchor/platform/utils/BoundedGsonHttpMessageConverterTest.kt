package org.stellar.anchor.platform.utils

import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.stellar.anchor.api.platform.PatchTransactionsRequest
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.util.GsonUtils

class BoundedGsonHttpMessageConverterTest {
  private val gson = GsonUtils.getInstance()
  // java.util.List<RpcRequest> exactly as the Java controller declares it (a Kotlin
  // List<RpcRequest>
  // would carry a `? extends` wildcard)
  private val rpcType: Type =
    TypeToken.getParameterized(MutableList::class.java, RpcRequest::class.java).type
  private val mapType: Type = object : TypeToken<Map<String, Any>>() {}.type
  private val stringListType: Type = object : TypeToken<List<String>>() {}.type

  private fun converter(
    maxNodes: Int = 1_000_000,
    maxDepth: Int = 1_000,
    rpcMaxNodes: Int = 1_000_000,
    batchSizeLimit: Int = 1_000_000,
    maxPatchRecords: Int = 1_000_000,
  ) =
    BoundedGsonHttpMessageConverter(
      gson,
      BoundedGsonHttpMessageConverter.Limits.builder()
        .maxNodes(maxNodes)
        .maxDepth(maxDepth)
        .rpcMaxNodes(rpcMaxNodes)
        .batchSizeLimit(batchSizeLimit)
        .maxPatchRecords(maxPatchRecords)
        .build(),
    )

  private fun BoundedGsonHttpMessageConverter.parse(type: Type, body: String): Any? {
    val message = MockHttpInputMessage(body.toByteArray())
    message.headers.contentType = MediaType.APPLICATION_JSON
    return read(type, null, message)
  }

  // one request: object, id, jsonrpc, method, params object, and one param = 6 values
  private val request = """{"id":1,"jsonrpc":"2.0","method":"m","params":{"a":1}}"""

  private fun batch(count: Int) = List(count) { request }.joinToString(",", "[", "]")

  private fun causeOf(body: String, type: Type, c: BoundedGsonHttpMessageConverter): Throwable =
    assertThrows(HttpMessageNotReadableException::class.java) { c.parse(type, body) }.cause!!

  @Test
  fun `an rpc batch with exactly rpcMaxNodes values and batchSizeLimit elements reads like gson`() {
    // root array + 3 requests of 6 values = 19 values
    val body = batch(3)
    val read = converter(rpcMaxNodes = 19, batchSizeLimit = 3).parse(rpcType, body)
    assertEquals(gson.fromJson<List<RpcRequest>>(body, rpcType), read)
    assertEquals(3, (read as List<*>).size)
    assertEquals(mapOf("a" to 1L), (read[0] as RpcRequest).params)
  }

  @Test
  fun `an rpc batch with one value over rpcMaxNodes throws the node limit`() {
    val cause = causeOf(batch(3), rpcType, converter(rpcMaxNodes = 18, batchSizeLimit = 3))
    val ex = assertInstanceOf(JsonStructureLimitException::class.java, cause)
    assertEquals(JsonStructureLimitException.NODES, ex.limitName)
    assertEquals(18, ex.limit)
  }

  @Test
  fun `an rpc batch with one element over batchSizeLimit throws the root array limit`() {
    val cause = causeOf(batch(4), rpcType, converter(rpcMaxNodes = 1_000, batchSizeLimit = 3))
    val ex = assertInstanceOf(JsonArrayLimitException::class.java, cause)
    assertNull(ex.member)
    assertEquals(3, ex.limit)
  }

  @Test
  fun `an rpc body uses rpcMaxNodes and not maxNodes`() {
    // maxNodes is far below the body size, rpcMaxNodes is not
    converter(maxNodes = 3, rpcMaxNodes = 19, batchSizeLimit = 3).parse(rpcType, batch(3))
  }

  @Test
  fun `an rpc body nested deeper than maxDepth throws the depth limit`() {
    // root array -> request -> params -> a = depth 3
    val body = """[{"id":1,"jsonrpc":"2.0","method":"m","params":{"a":{"b":1}}}]"""
    val cause = causeOf(body, rpcType, converter(maxDepth = 3))
    assertEquals(
      JsonStructureLimitException.DEPTH,
      (cause as JsonStructureLimitException).limitName
    )
    converter(maxDepth = 4).parse(rpcType, body)
  }

  @Test
  fun `a patch body with exactly maxPatchRecords records reads like gson`() {
    val body = """{"records":[{"transaction":{"id":"a"}},{"transaction":{"id":"b"}}]}"""
    val read = converter(maxPatchRecords = 2).parse(PatchTransactionsRequest::class.java, body)
    // PatchTransactionRequest has no equals: compare the serialized forms
    assertEquals(
      gson.toJson(gson.fromJson(body, PatchTransactionsRequest::class.java)),
      gson.toJson(read),
    )
    assertEquals(2, (read as PatchTransactionsRequest).records.size)
  }

  @Test
  fun `a patch body with one record over maxPatchRecords throws the records array limit`() {
    val body = """{"records":[{"transaction":{"id":"a"}},{"transaction":{"id":"b"}},{}]}"""
    val cause = causeOf(body, PatchTransactionsRequest::class.java, converter(maxPatchRecords = 2))
    val ex = assertInstanceOf(JsonArrayLimitException::class.java, cause)
    assertEquals("records", ex.member)
    assertEquals(2, ex.limit)
  }

  @Test
  fun `a body of any other type is limited by maxNodes only`() {
    // {"a":[1,2]} is an object, an array and two numbers = 4 values
    val body = """{"a":[1,2]}"""
    assertEquals(
      gson.fromJson<Map<String, Any>>(body, mapType),
      converter(maxNodes = 4, batchSizeLimit = 1, maxPatchRecords = 1).parse(mapType, body),
    )
    val ex =
      assertInstanceOf(
        JsonStructureLimitException::class.java,
        causeOf(body, mapType, converter(maxNodes = 3)),
      )
    assertEquals(3, ex.limit)
  }

  @Test
  fun `a body of any other type is limited by maxDepth`() {
    val body = """{"a":{"b":1}}"""
    converter(maxDepth = 2).parse(mapType, body)
    val ex =
      assertInstanceOf(
        JsonStructureLimitException::class.java,
        causeOf(body, mapType, converter(maxDepth = 1)),
      )
    assertEquals(JsonStructureLimitException.DEPTH, ex.limitName)
  }

  @Test
  fun `the batch and records limits do not apply to other types`() {
    val c = converter(batchSizeLimit = 2, maxPatchRecords = 2)
    assertEquals(listOf("a", "b", "c", "d"), c.parse(stringListType, """["a","b","c","d"]"""))
    assertNotNull(c.parse(mapType, """{"records":[1,2,3,4,5]}"""))
  }

  @Test
  fun `malformed json surfaces gson's syntax error and not a limit`() {
    val cause = causeOf("""[{"id":""", rpcType, converter())
    assertInstanceOf(JsonParseException::class.java, cause)
    assert(cause !is JsonStructureLimitException && cause !is JsonArrayLimitException)
  }

  @Test
  fun `an array where an object is expected surfaces gson's type error and not a limit`() {
    val cause = causeOf("[]", PatchTransactionsRequest::class.java, converter())
    assertInstanceOf(JsonSyntaxException::class.java, cause)
  }

  @Test
  fun `data after the root value is rejected`() {
    val cause = causeOf("""{"a":1} {"b":2}""", mapType, converter())
    assertInstanceOf(JsonSyntaxException::class.java, cause)
  }
}
