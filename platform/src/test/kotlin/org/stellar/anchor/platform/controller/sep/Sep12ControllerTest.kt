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
import org.springframework.web.multipart.MultipartHttpServletRequest
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerResponse
import org.stellar.anchor.auth.WebAuthJwt
import org.stellar.anchor.filter.AbstractJwtFilter.JWT_TOKEN
import org.stellar.anchor.sep12.Sep12Service
import org.stellar.anchor.util.Log

@ExtendWith(LockAndMockTest::class)
class Sep12ControllerTest {
  @MockK(relaxed = true) private lateinit var sep12Service: Sep12Service
  @MockK(relaxed = true) private lateinit var logger: Logger

  private lateinit var controller: Sep12Controller

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    controller = Sep12Controller(sep12Service)
  }

  private fun requestWithToken(): HttpServletRequest {
    val request = mockk<HttpServletRequest>(relaxed = true)
    every { request.getAttribute(any()) } returns null
    every { request.getAttribute(JWT_TOKEN) } returns mockk<WebAuthJwt>(relaxed = true)
    return request
  }

  // ANCHOR-1395 (HackerOne #4107899 follow-up): PUT /customer logs the whole request at DEBUG;
  // bank/KYC fields must never appear, even though this is the JSON path and was always capable
  // of honoring @PII -- nothing on the DTO was annotated until now.
  @Test
  @LockAndMockStatic([Log::class])
  fun `test putCustomer does not log bank or KYC fields`() {
    every { Log.getLogger() } returns logger
    val loggedLines = mutableListOf<String>()
    every { logger.debug(capture(loggedLines)) } answers {}

    every { sep12Service.putCustomer(any(), any()) } returns
      Sep12PutCustomerResponse.builder().id("customer-1").build()

    val putCustomerRequest =
      Sep12PutCustomerRequest.builder()
        .account("GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO")
        .type("sep31-sender")
        .firstName("Jamie")
        .bankAccountNumber("123456789")
        .bankName("Bank of Stellar")
        .idNumber("P1234567")
        .emailAddress("jamie@example.com")
        .build()

    controller.putCustomer(requestWithToken(), putCustomerRequest)

    assertTrue(loggedLines.isNotEmpty())
    val allLogged = loggedLines.joinToString("\n")
    assertFalse(allLogged.contains("Jamie"))
    assertFalse(allLogged.contains("123456789"))
    assertFalse(allLogged.contains("Bank of Stellar"))
    assertFalse(allLogged.contains("P1234567"))
    assertFalse(allLogged.contains("jamie@example.com"))
    assertTrue(allLogged.contains("sep31-sender"))
  }

  // Same finding, the multipart variant: the raw parameter map used to be logged directly, which
  // @PII cannot reach at all (a Map has no declared fields) -- now it logs the typed, annotated
  // DTO built from that same map instead.
  @Test
  @LockAndMockStatic([Log::class])
  fun `test putCustomerMultipart does not log bank or KYC fields`() {
    every { Log.getLogger() } returns logger
    val loggedLines = mutableListOf<String>()
    every { logger.debug(capture(loggedLines)) } answers {}

    every { sep12Service.putCustomer(any(), any()) } returns
      Sep12PutCustomerResponse.builder().id("customer-1").build()

    val request = mockk<MultipartHttpServletRequest>(relaxed = true)
    every { request.getAttribute(any()) } returns null
    every { request.getAttribute(JWT_TOKEN) } returns mockk<WebAuthJwt>(relaxed = true)
    every { request.parameterMap } returns
      mapOf(
        "account" to arrayOf("GBLGJA4TUN5XOGTV6WO2BWYUI2OZR5GYQ5PDPCRMQ5XEPJOYWB2X4CJO"),
        "type" to arrayOf("sep31-sender"),
        "first_name" to arrayOf("Jamie"),
        "bank_account_number" to arrayOf("987654321"),
      )
    every { request.multiFileMap } returns org.springframework.util.LinkedMultiValueMap()

    controller.putCustomerMultipart(request)

    assertTrue(loggedLines.isNotEmpty())
    val allLogged = loggedLines.joinToString("\n")
    assertFalse(allLogged.contains("Jamie"))
    assertFalse(allLogged.contains("987654321"))
    assertTrue(allLogged.contains("sep31-sender"))
  }
}
