package org.stellar.anchor.platform.controller.platform

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.api.rpc.RpcResponse
import org.stellar.anchor.platform.service.RpcService
import org.stellar.anchor.platform.utils.BoundedGsonHttpMessageConverter
import org.stellar.anchor.platform.utils.RequestBodySizeLimitFilter
import org.stellar.anchor.util.GsonUtils

/** The RPC route through the real filter, converter and exception handler, with the defaults. */
class PlatformRpcJsonLimitsTest {
  private val gson = GsonUtils.getInstance()
  private lateinit var rpcService: RpcService

  private val maxBodySize = 1_048_576L
  private val rpcMaxNodes = 10_000
  private val batchSizeLimit = 40

  @BeforeEach
  fun setup() {
    rpcService = mockk()
    every { rpcService.handle(any()) } returns emptyList<RpcResponse>()
  }

  private val mvc: MockMvc
    get() =
      MockMvcBuilders.standaloneSetup(PlatformRpcController(rpcService))
        .addFilters<StandaloneMockMvcBuilder>(RequestBodySizeLimitFilter(maxBodySize))
        .setControllerAdvice(PlatformControllerExceptionHandler())
        .setMessageConverters(
          BoundedGsonHttpMessageConverter(
            gson,
            BoundedGsonHttpMessageConverter.Limits.builder()
              .maxNodes(50_000)
              .maxDepth(32)
              .rpcMaxNodes(rpcMaxNodes)
              .batchSizeLimit(batchSizeLimit)
              .maxPatchRecords(1000)
              .build(),
          )
        )
        .build()

  private fun send(body: String): ResultActions =
    mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(body))

  private fun request(params: String, id: Int = 1) =
    """{"jsonrpc":"2.0","id":$id,"method":"notify_x","params":$params}"""

  /** A single request whose params is an array of `empties` empty objects. */
  private fun emptiesBody(empties: Int) =
    "[" + request("[" + List(empties) { "{}" }.joinToString(",") + "]") + "]"

  private fun tooComplex(result: ResultActions) {
    result
      .andExpect(status().isBadRequest)
      .andExpect(content().json("""{"error":"The request body is too complex."}""", true))
    verify(exactly = 0) { rpcService.handle(any()) }
  }

  @Test
  fun `the reporter body is refused with 400 and never reaches the service`() {
    // 330,000 empty objects in params: 990 KB, under the 1 MiB body cap
    val body = emptiesBody(330_000)
    assertTrue(body.length < maxBodySize, "the body must pass the byte cap: ${body.length}")
    assertTrue(body.length > 990_000, "the body must be the reporter's size: ${body.length}")

    tooComplex(send(body))
  }

  @Test
  fun `a body with exactly rpc max_json_nodes values reaches the service`() {
    // root array + request object + id + jsonrpc + method + params array = 6, plus the empties
    val body = emptiesBody(rpcMaxNodes - 6)

    send(body).andExpect(status().isOk)

    val requests = slot<List<RpcRequest>>()
    verify(exactly = 1) { rpcService.handle(capture(requests)) }
    assertEquals(1, requests.captured.size)
    assertEquals(rpcMaxNodes - 6, (requests.captured[0].params as List<*>).size)
  }

  @Test
  fun `a body with one value over rpc max_json_nodes is refused`() {
    tooComplex(send(emptiesBody(rpcMaxNodes - 6 + 1)))
  }

  @Test
  fun `a batch of exactly batch_size_limit requests reaches the service with all of them`() {
    val body = List(batchSizeLimit) { request("""{"a":1}""", it) }.joinToString(",", "[", "]")

    send(body).andExpect(status().isOk)

    val requests = slot<List<RpcRequest>>()
    verify(exactly = 1) { rpcService.handle(capture(requests)) }
    assertEquals(batchSizeLimit, requests.captured.size)
    assertEquals(
      (0 until batchSizeLimit).map { it.toLong() },
      requests.captured.map { it.id },
    )
  }

  @Test
  fun `a batch of batch_size_limit plus one answers 200 with the batch limit error and never reaches the service`() {
    val body = List(batchSizeLimit + 1) { request("""{"a":1}""", it) }.joinToString(",", "[", "]")

    val response = send(body).andExpect(status().isOk).andReturn().response.contentAsString

    val parsed = gson.fromJson(response, Array<RpcResponse>::class.java)
    assertEquals(1, parsed.size)
    assertEquals("RPC batch size limit[40] exceeded", parsed[0].error.message)
    verify(exactly = 0) { rpcService.handle(any()) }
  }

  @Test
  fun `a batch of 40 realistic requests reaches the service with their params intact`() {
    // 244 values of params per request: 16 fixed plus 57 fee detail entries of 4 values each,
    // so the whole batch stays under rpc.max_json_nodes (1 + 40 x 249 = 9961)
    fun params(i: Int): String {
      val details =
        List(57) { """{"name":"fee-$it","description":"fee $it of request $i","amount":"$it.5"}""" }
      return """{"transaction":{"id":"tx-$i",
        "amount_in":{"amount":"100","asset":"stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"},
        "amount_out":{"amount":"99","asset":"iso4217:USD"},
        "amount_fee":{"amount":"1","asset":"iso4217:USD"},
        "fee_details":{"total":"1","asset":"iso4217:USD","details":[${details.joinToString(",")}]},
        "message":"completed"}}"""
    }
    val sent = List(batchSizeLimit) { params(it) }
    val body = sent.mapIndexed { i, p -> request(p, i) }.joinToString(",", "[", "]")
    assertTrue(body.length < maxBodySize)

    send(body).andExpect(status().isOk)

    val requests = slot<List<RpcRequest>>()
    verify(exactly = 1) { rpcService.handle(capture(requests)) }
    assertEquals(batchSizeLimit, requests.captured.size)
    sent.forEachIndexed { i, p ->
      assertEquals(gson.fromJson(p, Any::class.java), requests.captured[i].params, "params of $i")
      assertEquals("notify_x", requests.captured[i].method)
    }
  }

  @Test
  fun `an object where the batch array is expected answers the generic 400 and never reaches the service`() {
    send("{}")
      .andExpect(status().isBadRequest)
      .andExpect(content().json("""{"error":"Your request body is wrong in some way."}""", true))
    verify(exactly = 0) { rpcService.handle(any()) }
  }

  @Test
  fun `a body over both the byte cap and the node limit is refused with 413`() {
    // 600,000 empty objects is about 1.8 MB: the byte filter answers before any parsing
    send(emptiesBody(600_000))
      .andExpect(status().isPayloadTooLarge)
      .andExpect(content().json("""{"error":"The request body is too large."}""", true))
    verify(exactly = 0) { rpcService.handle(any()) }
  }
}
