package org.stellar.anchor.platform.controller.platform

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.converter.json.GsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.stellar.anchor.api.platform.PatchTransactionsResponse
import org.stellar.anchor.platform.config.PlatformServerConfig
import org.stellar.anchor.platform.service.TransactionService
import org.stellar.anchor.platform.utils.RequestBodySizeLimitFilter
import org.stellar.anchor.util.GsonUtils

class PlatformRequestLimitsTest {
  private lateinit var transactionService: TransactionService
  private val platformServerConfig = PlatformServerConfig(mockk()).apply { maxPatchRecords = 2 }

  @BeforeEach
  fun setup() {
    transactionService = mockk()
    every { transactionService.patchTransactions(any()) } returns
      PatchTransactionsResponse(emptyList())
  }

  private fun mvc(maxBodySize: Long): MockMvc =
    MockMvcBuilders.standaloneSetup(PlatformController(transactionService, platformServerConfig))
      .addFilters<StandaloneMockMvcBuilder>(RequestBodySizeLimitFilter(maxBodySize))
      .setControllerAdvice(PlatformControllerExceptionHandler())
      .setMessageConverters(GsonHttpMessageConverter(GsonUtils.getInstance()))
      .build()

  private fun recordsBody(count: Int, message: String = "m"): String =
    GsonUtils.getInstance()
      .toJson(
        mapOf(
          "records" to
            (1..count).map { mapOf("transaction" to mapOf("id" to "id-$it", "message" to message)) }
        )
      )

  @Test
  fun `a body over the size limit is rejected with 413 before it reaches the controller`() {
    mvc(maxBodySize = 1024)
      .perform(
        patch("/transactions")
          .contentType(MediaType.APPLICATION_JSON)
          .content(recordsBody(1, message = "A".repeat(4096)))
      )
      .andExpect(status().isPayloadTooLarge)

    verify(exactly = 0) { transactionService.patchTransactions(any()) }
  }

  @Test
  fun `a body within the size limit reaches the controller`() {
    mvc(maxBodySize = 1024 * 1024)
      .perform(
        patch("/transactions").contentType(MediaType.APPLICATION_JSON).content(recordsBody(2))
      )
      .andExpect(status().isOk)

    verify(exactly = 1) { transactionService.patchTransactions(any()) }
  }

  @Test
  fun `more records than max_patch_records are rejected without patching anything`() {
    mvc(maxBodySize = 1024 * 1024)
      .perform(
        patch("/transactions").contentType(MediaType.APPLICATION_JSON).content(recordsBody(3))
      )
      .andExpect(status().isBadRequest)

    verify(exactly = 0) { transactionService.patchTransactions(any()) }
  }

  @Test
  fun `a streamed body stops being read once it passes the size limit`() {
    val source = ByteArrayInputStream(ByteArray(64))
    val delegate =
      object : ServletInputStream() {
        override fun read(): Int = source.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)

        override fun isFinished(): Boolean = source.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener?) {}
      }
    val limited = RequestBodySizeLimitFilter.LimitedInputStream(delegate, 16)

    assertThrows(RequestBodySizeLimitFilter.RequestBodyTooLargeException::class.java) {
      limited.readAllBytes()
    }
  }

  @Test
  fun `a body that passes the size limit while being parsed is reported as 413`() {
    val ex =
      HttpMessageNotReadableException(
        "I/O error while reading input message",
        RequestBodySizeLimitFilter.RequestBodyTooLargeException(16),
        mockk<HttpInputMessage>(),
      )

    val response = PlatformControllerExceptionHandler().handleRandomException(ex)

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.statusCode)
  }
}
