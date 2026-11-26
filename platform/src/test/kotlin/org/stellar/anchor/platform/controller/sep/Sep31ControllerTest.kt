package org.stellar.anchor.platform.controller.sep

import io.mockk.*
import io.mockk.impl.annotations.MockK
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.sep.sep31.Sep31GetTransactionResponse
import org.stellar.anchor.api.sep.sep31.Sep31PatchTransactionRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest.Sep31TxnFields
import org.stellar.anchor.auth.WebAuthJwt
import org.stellar.anchor.filter.AbstractJwtFilter.JWT_TOKEN
import org.stellar.anchor.platform.utils.RequestLoggerFilter.ATTRIBUTE_CLIENT_IP_ADDRESS
import org.stellar.anchor.platform.utils.TransactionCreationRateLimiter
import org.stellar.anchor.sep31.Sep31Service
import org.stellar.anchor.util.Log

// ANCHOR-1540 (HackerOne #4207658): the SEP-31 request lines are written at DEBUG through
// `debugF`, which renders the object with `toString()` and never consults @PII.
@ExtendWith(LockAndMockTest::class)
class Sep31ControllerTest {
  @MockK(relaxed = true) private lateinit var sep31Service: Sep31Service
  @MockK(relaxed = true) private lateinit var rateLimiter: TransactionCreationRateLimiter

  private lateinit var controller: Sep31Controller
  private val logger = CapturingLogger()

  private val receiverFields =
    Sep31TxnFields(
      hashMapOf(
        "receiver_account_number" to "123456789012345678",
        "receiver_routing_number" to "021000021",
        "type" to "SWIFT",
      )
    )

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    controller = Sep31Controller(sep31Service, rateLimiter)
  }

  private fun requestWithToken(clientIp: String? = "203.0.113.9"): HttpServletRequest {
    val request = mockk<HttpServletRequest>(relaxed = true)
    every { request.getAttribute(any()) } returns null
    every { request.getAttribute(JWT_TOKEN) } returns mockk<WebAuthJwt>(relaxed = true)
    every { request.getAttribute(ATTRIBUTE_CLIENT_IP_ADDRESS) } returns clientIp
    return request
  }

  // DBGLOG-01, DBGLOG-02
  @Test
  @LockAndMockStatic([Log::class])
  fun `test postTransaction DEBUG line excludes receiver fields and client IP and keeps diagnostics`() {
    every { Log.getLogger() } returns logger

    val request = Sep31PostTransactionRequest()
    request.amount = "100.4207"
    request.assetCode = "USD"
    request.quoteId = "quote-4207658"
    request.fields = receiverFields

    controller.postTransaction(requestWithToken(), request)

    assertTrue(logger.debugLines.isNotEmpty())
    val allLogged = logger.debugLines.joinToString("\n")
    assertFalse(allLogged.contains("123456789012345678"))
    assertFalse(allLogged.contains("021000021"))
    assertFalse(allLogged.contains("203.0.113.9"))
    assertTrue(allLogged.contains("100.4207"))
    assertTrue(allLogged.contains("USD"))
    assertTrue(allLogged.contains("quote-4207658"))
  }

  // Edge case: the client IP is null when the request logger is disabled (the shipped default)
  @Test
  @LockAndMockStatic([Log::class])
  fun `test postTransaction with a null client IP logs the line without throwing`() {
    every { Log.getLogger() } returns logger

    val request = Sep31PostTransactionRequest()
    request.amount = "100.4207"
    request.assetCode = "USD"
    request.fields = receiverFields

    controller.postTransaction(requestWithToken(clientIp = null), request)

    assertTrue(logger.debugLines.isNotEmpty())
    val allLogged = logger.debugLines.joinToString("\n")
    assertTrue(allLogged.contains("100.4207"))
    assertFalse(allLogged.contains("123456789012345678"))
  }

  // DBGLOG-03, DBGLOG-04
  @Test
  @LockAndMockStatic([Log::class])
  fun `test patchTransaction DEBUG line excludes receiver fields and keeps the id`() {
    every { Log.getLogger() } returns logger
    every { sep31Service.patchTransaction(any(), any()) } returns
      mockk<Sep31GetTransactionResponse>(relaxed = true)

    val request = Sep31PatchTransactionRequest.builder().fields(receiverFields).build()

    controller.patchTransaction(requestWithToken(), "txn-id-4207658", request)

    assertTrue(logger.debugLines.isNotEmpty())
    val allLogged = logger.debugLines.joinToString("\n")
    assertFalse(allLogged.contains("123456789012345678"))
    assertFalse(allLogged.contains("021000021"))
    assertTrue(allLogged.contains("txn-id-4207658"))
  }

  // DBGLOG-08
  @Test
  @LockAndMockStatic([Log::class])
  fun `test patchTransaction with a null body logs the id without throwing`() {
    every { Log.getLogger() } returns logger
    every { sep31Service.patchTransaction(any(), any()) } returns
      mockk<Sep31GetTransactionResponse>(relaxed = true)

    controller.patchTransaction(requestWithToken(), "txn-id-4207658", null)

    assertTrue(logger.debugLines.isNotEmpty())
    assertTrue(logger.debugLines.joinToString("\n").contains("txn-id-4207658"))
  }

  // DBGLOG-05: the exclusion lives on the DTO, so any JSON-based Log method honors it
  @Test
  @LockAndMockStatic([Log::class])
  fun `test Sep31PatchTransactionRequest fields are excluded by every JSON Log method`() {
    every { Log.getLogger() } returns logger
    val request = Sep31PatchTransactionRequest.builder().id("txn-1").fields(receiverFields).build()

    Log.info("patch:", request)
    Log.debug("patch:", request)
    Log.trace("patch:", request)

    // other threads may share the mocked logger, so look only at this test's own lines
    val ownLines = logger.allLines.filter { it.startsWith("patch:") }
    assertEquals(3, ownLines.size)
    val allLogged = ownLines.joinToString("\n")
    assertTrue(allLogged.contains("txn-1"))
    assertFalse(allLogged.contains("123456789012345678"))
    assertFalse(allLogged.contains("021000021"))
  }
}
