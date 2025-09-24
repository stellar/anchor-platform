package org.stellar.anchor.platform.rpc

import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.RECEIVE
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_31
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_38
import org.stellar.anchor.api.rpc.method.RequestCustomerInfoUpdateRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.Customers
import org.stellar.anchor.api.shared.StellarId
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.service.AnchorMetrics
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils

class RequestCustomerInfoUpdateHandlerTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"
  }

  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var eventService: EventService

  @MockK(relaxed = true) private lateinit var metricsService: MetricsService

  @MockK(relaxed = true) private lateinit var eventSession: Session

  @MockK(relaxed = true) private lateinit var sepTransactionCounter: Counter

  private lateinit var handler: RequestCustomerInfoUpdateHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), EventService.EventQueue.TRANSACTION) } returns
      eventSession
    this.handler =
      RequestCustomerInfoUpdateHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = RequestCustomerInfoUpdateRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    val spyTxn31 = spyk(txn31)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns spyTxn31
    every { spyTxn31.protocol } returns SEP_38.sep.toString()

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_customer_info_update] is not supported. Status[pending_receiver], kind[null], protocol[38], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = RequestCustomerInfoUpdateRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { requestValidator.validate(request) } throws
      InvalidParamsException(VALIDATION_ERROR_MESSAGE)

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(VALIDATION_ERROR_MESSAGE, ex.message?.trimIndent())

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_unsupportedStatus() {
    val request = RequestCustomerInfoUpdateRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = INCOMPLETE.toString()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_customer_info_update] is not supported. Status[incomplete], kind[receive], protocol[31], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_ok() {
    val actionRequiredBy = Instant.now().plusSeconds(100)
    val request =
      RequestCustomerInfoUpdateRequest.builder()
        .transactionId(TX_ID)
        .userActionRequiredBy(actionRequiredBy)
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    val sep31TxnCapture = slot<JdbcSep31Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep31Txn = JdbcSep31Transaction()
    expectedSep31Txn.status = PENDING_CUSTOMER_INFO_UPDATE.toString()
    expectedSep31Txn.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedSep31Txn.userActionRequiredBy = actionRequiredBy

    JSONAssert.assertEquals(
      gson.toJson(expectedSep31Txn),
      gson.toJson(sep31TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_31
    expectedResponse.kind = RECEIVE
    expectedResponse.status = PENDING_CUSTOMER_INFO_UPDATE
    expectedResponse.amountIn = Amount()
    expectedResponse.amountOut = Amount()
    expectedResponse.amountFee = Amount()
    expectedResponse.amountExpected = Amount()
    expectedResponse.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedResponse.customers = Customers(StellarId(), StellarId())
    expectedResponse.userActionRequiredBy = actionRequiredBy

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_31.sep.toString())
        .type(AnchorEvent.Type.TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture.captured),
      JSONCompareMode.STRICT
    )

    assertTrue(sep31TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep31TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep6_unsupportedStatus() {
    val request =
      RequestCustomerInfoUpdateRequest.builder()
        .transactionId(TX_ID)
        .requiredCustomerInfoUpdates(listOf("email_address", "family_name", "given_name"))
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_USR_TRANSFER_START.toString()
    txn6.kind = DEPOSIT.toString()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_customer_info_update] is not supported. Status[pending_user_transfer_start], kind[DEPOSIT], protocol[6], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "deposit, incomplete",
        "deposit, pending_anchor",
        "deposit, pending_customer_info_update",
        "deposit-exchange, incomplete",
        "deposit-exchange, pending_anchor",
        "deposit-exchange, pending_customer_info_update",
        "withdrawal, incomplete",
        "withdrawal, pending_anchor",
        "withdrawal, pending_customer_info_update",
        "withdrawal-exchange, incomplete",
        "withdrawal-exchange, pending_anchor",
        "withdrawal-exchange, pending_customer_info_update"
      ]
  )
  fun test_handle_sep6_ok(kind: String, status: String) {
    val request =
      RequestCustomerInfoUpdateRequest.builder()
        .transactionId(TX_ID)
        .requiredCustomerInfoUpdates(listOf("email_address", "family_name", "given_name"))
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = status
    txn6.kind = kind
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep6") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep6Txn = JdbcSep6Transaction()
    expectedSep6Txn.kind = kind
    expectedSep6Txn.status = PENDING_CUSTOMER_INFO_UPDATE.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.requiredCustomerInfoUpdates =
      listOf("email_address", "family_name", "given_name")

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = PlatformTransactionData.Sep.SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_CUSTOMER_INFO_UPDATE
    expectedResponse.amountExpected = Amount(null, "")
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.requiredCustomerInfoUpdates =
      listOf("email_address", "family_name", "given_name")

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(PlatformTransactionData.Sep.SEP_6.sep.toString())
        .type(AnchorEvent.Type.TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture.captured),
      JSONCompareMode.STRICT
    )

    assertTrue(sep6TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep6TxnCapture.captured.updatedAt <= endDate)
  }
}
