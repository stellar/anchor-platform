package org.stellar.anchor.platform.controller.platform

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.stellar.anchor.api.platform.PatchTransactionsRequest
import org.stellar.anchor.api.platform.PatchTransactionsResponse
import org.stellar.anchor.platform.config.PlatformServerConfig
import org.stellar.anchor.platform.service.TransactionService
import org.stellar.anchor.platform.utils.BoundedGsonHttpMessageConverter
import org.stellar.anchor.platform.utils.RequestBodySizeLimitFilter
import org.stellar.anchor.util.GsonUtils

/** `PATCH /transactions` and the general limits through the real filter, converter and handler. */
class PlatformJsonLimitsTest {
  private val gson = GsonUtils.getInstance()
  private lateinit var transactionService: TransactionService

  @BeforeEach
  fun setup() {
    transactionService = mockk()
    every { transactionService.patchTransactions(any()) } returns
      PatchTransactionsResponse(emptyList())
  }

  private fun mvc(
    maxNodes: Int = 50_000,
    maxDepth: Int = 32,
    maxPatchRecords: Int = 1000,
    maxBodySize: Long = 1_048_576L,
  ): MockMvc =
    MockMvcBuilders.standaloneSetup(
        PlatformController(
          transactionService,
          PlatformServerConfig(mockk()).apply { this.maxPatchRecords = maxPatchRecords },
        )
      )
      .addFilters<StandaloneMockMvcBuilder>(RequestBodySizeLimitFilter(maxBodySize))
      .setControllerAdvice(PlatformControllerExceptionHandler())
      .setMessageConverters(
        BoundedGsonHttpMessageConverter(
          gson,
          BoundedGsonHttpMessageConverter.Limits.builder()
            .maxNodes(maxNodes)
            .maxDepth(maxDepth)
            .rpcMaxNodes(10_000)
            .batchSizeLimit(40)
            .maxPatchRecords(maxPatchRecords)
            .build(),
        )
      )
      .build()

  private fun send(mvc: MockMvc, body: String): ResultActions =
    mvc.perform(patch("/transactions").contentType(MediaType.APPLICATION_JSON).content(body))

  private fun tooComplex(result: ResultActions) {
    result
      .andExpect(status().isBadRequest)
      .andExpect(content().json("""{"error":"The request body is too complex."}""", true))
    verify(exactly = 0) { transactionService.patchTransactions(any()) }
  }

  // root object, records array, record, transaction, id, message = 6 values at depth 4
  private val smallBody = """{"records":[{"transaction":{"id":"a","message":"b"}}]}"""

  /** A record of 30 values: amounts, fee details with three entries, memo and external id. */
  private fun realisticRecord(i: Int) =
    """{"transaction":{"id":"tx-$i","status":"completed","message":"done",
      "amount_in":{"amount":"100","asset":"iso4217:USD"},
      "amount_out":{"amount":"99","asset":"iso4217:USD"},
      "fee_details":{"total":"1","asset":"iso4217:USD","details":[
        {"name":"a","description":"fee a","amount":"0.5"},
        {"name":"b","description":"fee b","amount":"0.25"},
        {"name":"c","description":"fee c","amount":"0.25"}]},
      "external_transaction_id":"ext-$i","memo":"m$i","memo_type":"text"}}"""

  @Test
  fun `max_patch_records records of realistic content reach the service`() {
    val body = List(1000) { realisticRecord(it) }.joinToString(",", """{"records":[""", "]}")
    assertTrue(body.length < 1_048_576, "the body must pass the byte cap: ${body.length}")

    send(mvc(), body).andExpect(status().isOk)

    val request = slot<PatchTransactionsRequest>()
    verify(exactly = 1) { transactionService.patchTransactions(capture(request)) }
    assertEquals(1000, request.captured.records.size)
    assertEquals("tx-999", request.captured.records[999].transaction.id)
    assertEquals(
      "0.25",
      request.captured.records[999].transaction.feeDetails.details[2].amount,
    )
  }

  @Test
  fun `one record over max_patch_records answers 400 and patches nothing`() {
    val body = List(1001) { realisticRecord(it) }.joinToString(",", """{"records":[""", "]}")

    send(mvc(), body)
      .andExpect(status().isBadRequest)
      .andExpect(
        content().json("""{"error":"The number of records exceeds the limit of 1000."}""", true)
      )
    verify(exactly = 0) { transactionService.patchTransactions(any()) }
  }

  @Test
  fun `330000 empty records are refused with 400 after allocating less than 2 MiB`() {
    // the reporter's shape on PATCH: 990 KB, under the 1 MiB body cap
    val body = """{"records":[""" + List(330_000) { "{}" }.joinToString(",") + "]}"
    assertTrue(body.length < 1_048_576 && body.length > 990_000, "body size ${body.length}")
    val mvc = mvc()
    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val id = Thread.currentThread().id

    // the first request warms up MockMvc and class loading, and is refused like the second
    send(mvc, body).andExpect(status().isBadRequest)
    val request = patch("/transactions").contentType(MediaType.APPLICATION_JSON).content(body)
    val before = bean.getThreadAllocatedBytes(id)
    val result = mvc.perform(request)
    val allocated = bean.getThreadAllocatedBytes(id) - before

    result
      .andExpect(status().isBadRequest)
      .andExpect(
        content().json("""{"error":"The number of records exceeds the limit of 1000."}""", true)
      )
    assertTrue(allocated < 2 * 1024 * 1024, "allocated $allocated bytes")
    verify(exactly = 0) { transactionService.patchTransactions(any()) }
  }

  @Test
  fun `a body with exactly max_json_nodes values reaches the controller`() {
    send(mvc(maxNodes = 6), smallBody).andExpect(status().isOk)

    verify(exactly = 1) { transactionService.patchTransactions(any()) }
  }

  @Test
  fun `a body with one value over max_json_nodes is refused`() {
    tooComplex(send(mvc(maxNodes = 5), smallBody))
  }

  @Test
  fun `a body nested exactly max_json_depth deep reaches the controller`() {
    send(mvc(maxDepth = 4), smallBody).andExpect(status().isOk)

    verify(exactly = 1) { transactionService.patchTransactions(any()) }
  }

  @Test
  fun `a body nested one level over max_json_depth is refused`() {
    tooComplex(send(mvc(maxDepth = 3), smallBody))
  }

  @Test
  fun `values inside an undeclared member do not count toward the node limit`() {
    val body =
      """{"records":[{"transaction":{"id":"a","message":"b"}}],"unknown":[1,2,3,4,5,6,7,8,9,10]}"""

    send(mvc(maxNodes = 6), body).andExpect(status().isOk)

    verify(exactly = 1) { transactionService.patchTransactions(any()) }
  }

  @Test
  fun `malformed json answers the generic 400`() {
    send(mvc(), """{"records":[{"transaction":""")
      .andExpect(status().isBadRequest)
      .andExpect(content().json("""{"error":"Your request body is wrong in some way."}""", true))
    verify(exactly = 0) { transactionService.patchTransactions(any()) }
  }

  @Test
  fun `an array where an object is expected answers the generic 400`() {
    send(mvc(), "[]")
      .andExpect(status().isBadRequest)
      .andExpect(content().json("""{"error":"Your request body is wrong in some way."}""", true))
    verify(exactly = 0) { transactionService.patchTransactions(any()) }
  }
}
