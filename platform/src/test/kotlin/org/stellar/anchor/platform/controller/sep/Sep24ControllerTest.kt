package org.stellar.anchor.platform.controller.sep

import io.mockk.*
import io.mockk.impl.annotations.MockK
import jakarta.servlet.http.HttpServletRequest
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
}
