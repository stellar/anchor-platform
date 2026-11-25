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
import org.slf4j.Logger
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.sep.sep24.InteractiveTransactionResponse
import org.stellar.anchor.auth.WebAuthJwt
import org.stellar.anchor.filter.AbstractJwtFilter.JWT_TOKEN
import org.stellar.anchor.platform.utils.TransactionCreationRateLimiter
import org.stellar.anchor.sep24.Sep24Service
import org.stellar.anchor.util.Log

@ExtendWith(LockAndMockTest::class)
class Sep24ControllerTest {
  @MockK(relaxed = true) private lateinit var sep24Service: Sep24Service
  @MockK(relaxed = true) private lateinit var rateLimiter: TransactionCreationRateLimiter
  @MockK(relaxed = true) private lateinit var logger: Logger

  private lateinit var controller: Sep24Controller

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    controller = Sep24Controller(sep24Service, rateLimiter)
  }

  private fun requestWithToken(): HttpServletRequest {
    val request = mockk<HttpServletRequest>(relaxed = true)
    every { request.getAttribute(any()) } returns null
    every { request.getAttribute(JWT_TOKEN) } returns mockk<WebAuthJwt>(relaxed = true)
    return request
  }

  @Test
  @LockAndMockStatic([Log::class])
  fun `test deposit does not log the interactive URL bearer token`() {
    every { Log.getLogger() } returns logger
    val loggedLines = mutableListOf<String>()
    every { logger.info(capture(loggedLines)) } answers {}

    val itr =
      InteractiveTransactionResponse(
        "interactive_customer_info_needed",
        "https://anchor.example.com/interactive?token=secret-bearer-jwt-value",
        "a2392add-87c9-42f0-a5c1-5f1728030b68",
      )
    every { sep24Service.deposit(any(), any()) } returns itr

    controller.deposit(requestWithToken(), HashMap())

    assertFalse(loggedLines.any { it.contains("secret-bearer-jwt-value") })
    assertTrue(loggedLines.any { it.contains("interactive_customer_info_needed") })
    assertTrue(loggedLines.any { it.contains("a2392add-87c9-42f0-a5c1-5f1728030b68") })
  }

  @Test
  @LockAndMockStatic([Log::class])
  fun `test withdraw does not log the interactive URL bearer token`() {
    every { Log.getLogger() } returns logger
    val loggedLines = mutableListOf<String>()
    every { logger.info(capture(loggedLines)) } answers {}

    val itr =
      InteractiveTransactionResponse(
        "interactive_customer_info_needed",
        "https://anchor.example.com/interactive?token=another-secret-bearer-jwt",
        "b3392add-87c9-42f0-a5c1-5f1728030b69",
      )
    every { sep24Service.withdraw(any(), any()) } returns itr

    controller.withdraw(requestWithToken(), HashMap())

    assertFalse(loggedLines.any { it.contains("another-secret-bearer-jwt") })
    assertTrue(loggedLines.any { it.contains("interactive_customer_info_needed") })
    assertTrue(loggedLines.any { it.contains("b3392add-87c9-42f0-a5c1-5f1728030b69") })
  }

  // ANCHOR-1540 (HackerOne #4207658): the request body is a HashMap, which @PII cannot scrub
  // because an ExclusionStrategy is only asked about declared fields. The DEBUG line renders a
  // typed allowlist view instead.
  private val interactiveResponse =
    InteractiveTransactionResponse(
      "interactive_customer_info_needed",
      "https://anchor.example.com/interactive",
      "a2392add-87c9-42f0-a5c1-5f1728030b68",
    )

  private val allowlisted =
    mapOf(
      "asset_code" to "USDC",
      "asset_issuer" to "GISSUER4207658",
      "amount" to "98.76",
      "lang" to "pt-BR",
      "memo_type" to "hash",
      "refund_memo_type" to "text",
      "quote_id" to "quote-4207658",
      "source_asset" to "iso4217:BRL",
      "destination_asset" to "stellar:USDC:GISSUER4207658",
      "claimable_balance_supported" to "true",
    )

  private fun sensitiveRequest() =
    HashMap(
      allowlisted +
        mapOf(
          "email_address" to "victim@example.com",
          "customer_id" to "cust-4207658",
          "memo" to "memo-4207658",
          "refund_memo" to "refund-memo-4207658",
          "account" to "GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO",
          "bank_account_number" to "123456789012",
        )
    )

  private fun assertSanitizedDebugLine() {
    assertTrue(capturing.debugLines.isNotEmpty())
    val allLogged = capturing.debugLines.joinToString("\n")
    assertFalse(allLogged.contains("victim@example.com"))
    assertFalse(allLogged.contains("cust-4207658"))
    assertFalse(allLogged.contains("memo-4207658"))
    assertFalse(allLogged.contains("refund-memo-4207658"))
    assertFalse(allLogged.contains("GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO"))
    assertFalse(allLogged.contains("123456789012"))
    // every allowlisted value stays visible
    for ((name, value) in allowlisted) {
      assertTrue(allLogged.contains("\"$name\":\"$value\""), "missing allowlisted $name")
    }
    // the name of every received key stays visible, matched as a whole quoted string
    for (name in sensitiveRequest().keys) {
      assertTrue(allLogged.contains("\"$name\""), "missing key name $name")
    }
  }

  private val capturing = CapturingLogger()

  // DBGLOG-06, DBGLOG-07
  @Test
  @LockAndMockStatic([Log::class])
  fun `test deposit DEBUG line omits non-allowlisted values and keeps allowlisted ones`() {
    every { Log.getLogger() } returns capturing
    every { sep24Service.deposit(any(), any()) } returns interactiveResponse

    controller.deposit(requestWithToken(), sensitiveRequest())

    assertSanitizedDebugLine()
  }

  // DBGLOG-06, DBGLOG-07
  @Test
  @LockAndMockStatic([Log::class])
  fun `test withdraw DEBUG line omits non-allowlisted values and keeps allowlisted ones`() {
    every { Log.getLogger() } returns capturing
    every { sep24Service.withdraw(any(), any()) } returns interactiveResponse

    controller.withdraw(requestWithToken(), sensitiveRequest())

    assertSanitizedDebugLine()
  }

  // DBGLOG-06: the multipart handlers build the same map and reach the same log line
  @Test
  @LockAndMockStatic([Log::class])
  fun `test multipart deposit and withdraw DEBUG lines omit non-allowlisted values`() {
    every { Log.getLogger() } returns capturing
    every { sep24Service.deposit(any(), any()) } returns interactiveResponse
    every { sep24Service.withdraw(any(), any()) } returns interactiveResponse
    val parameterMap = sensitiveRequest().mapValues { arrayOf(it.value) }

    val depositRequest = requestWithToken()
    every { depositRequest.parameterMap } returns parameterMap
    controller.depositAllType(depositRequest)
    assertSanitizedDebugLine()

    val withdrawRequest = requestWithToken()
    every { withdrawRequest.parameterMap } returns parameterMap
    controller.withdrawAllType(withdrawRequest)
    assertEquals(1, capturing.debugLines.count { it.startsWith("/deposit") })
    assertEquals(1, capturing.debugLines.count { it.startsWith("/withdraw") })
    assertSanitizedDebugLine()
  }

  // DBGLOG-09
  @Test
  @LockAndMockStatic([Log::class])
  fun `test deposit and withdraw with an empty body log an empty key list without throwing`() {
    every { Log.getLogger() } returns capturing
    every { sep24Service.deposit(any(), any()) } returns interactiveResponse
    every { sep24Service.withdraw(any(), any()) } returns interactiveResponse

    controller.deposit(requestWithToken(), HashMap())
    controller.withdraw(requestWithToken(), HashMap())

    for (flow in listOf("/deposit", "/withdraw")) {
      val line = capturing.debugLines.single { it.startsWith(flow) }
      assertTrue(line.contains("\"receivedFields\":[]"), line)
    }
  }

  // Edge cases: an unknown SEP-9 key shows its name only; a null allowlisted value is omitted
  @Test
  @LockAndMockStatic([Log::class])
  fun `test deposit DEBUG line tolerates a null allowlisted value`() {
    every { Log.getLogger() } returns capturing
    every { sep24Service.deposit(any(), any()) } returns interactiveResponse
    val body = hashMapOf<String, String?>("asset_code" to null, "amount" to "5.5")

    @Suppress("UNCHECKED_CAST")
    controller.deposit(requestWithToken(), body as HashMap<String, String>)

    val allLogged = capturing.debugLines.joinToString("\n")
    assertTrue(allLogged.contains("5.5"))
    assertTrue(allLogged.contains("asset_code"))
  }
}
