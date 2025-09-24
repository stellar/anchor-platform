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
import org.junit.jupiter.params.provider.ValueSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.callback.CustomerIntegration
import org.stellar.anchor.api.callback.GetCustomerRequest
import org.stellar.anchor.api.callback.GetCustomerResponse
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.RECEIVE
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.*
import org.stellar.anchor.api.rpc.method.NotifyCustomerInfoUpdatedRequest
import org.stellar.anchor.api.sep.SepTransactionStatus
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
import org.stellar.anchor.platform.service.AnchorMetrics.PLATFORM_RPC_TRANSACTION
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils

class NotifyCustomerInfoUpdatedHandlerTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val TEST_CUSTOMER_ID = "testCustomerId"
    private const val TEST_CUSTOMER_TYPE = "type"
    private const val TEST_MESSAGE = "customer updated"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"
  }

  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var customerIntegration: CustomerIntegration

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var eventService: EventService

  @MockK(relaxed = true) private lateinit var metricsService: MetricsService

  @MockK(relaxed = true) private lateinit var eventSession: Session

  @MockK(relaxed = true) private lateinit var sepTransactionCounter: Counter

  private lateinit var handler: NotifyCustomerInfoUpdatedHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), EventService.EventQueue.TRANSACTION) } returns
      eventSession
    this.handler =
      NotifyCustomerInfoUpdatedHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        customerIntegration,
        assetService,
        eventService,
        metricsService
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = NotifyCustomerInfoUpdatedRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_CUSTOMER_INFO_UPDATE.toString()
    val spyTxn31 = spyk(txn31)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns spyTxn31
    every { spyTxn31.protocol } returns SEP_38.sep.toString()

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_customer_info_updated] is not supported. Status[pending_customer_info_update], kind[null], protocol[38], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_unsupportedStatus() {
    val request = NotifyCustomerInfoUpdatedRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = INCOMPLETE.toString()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_customer_info_updated] is not supported. Status[incomplete], kind[receive], protocol[31], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_invalidRequest() {
    val request = NotifyCustomerInfoUpdatedRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_CUSTOMER_INFO_UPDATE.toString()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { requestValidator.validate(request) } throws
      InvalidParamsException(VALIDATION_ERROR_MESSAGE)

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(VALIDATION_ERROR_MESSAGE, ex.message?.trimIndent())

    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_ok_without_id() {
    val request = NotifyCustomerInfoUpdatedRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_CUSTOMER_INFO_UPDATE.toString()
    txn31.userActionRequiredBy = Instant.now()
    val sep31TxnCapture = slot<JdbcSep31Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep31Txn = JdbcSep31Transaction()
    expectedSep31Txn.status = PENDING_RECEIVER.toString()
    expectedSep31Txn.updatedAt = sep31TxnCapture.captured.updatedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedSep31Txn),
      gson.toJson(sep31TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_31
    expectedResponse.kind = RECEIVE
    expectedResponse.status = PENDING_RECEIVER
    expectedResponse.amountIn = Amount()
    expectedResponse.amountOut = Amount()
    expectedResponse.amountFee = Amount()
    expectedResponse.amountExpected = Amount()
    expectedResponse.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedResponse.customers = Customers(StellarId(), StellarId())

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

  @ParameterizedTest
  @CsvSource(
    "ACCEPTED, pending_receiver, pending_receiver",
    "ACCEPTED, pending_customer_info_update, pending_receiver",
    "PROCESSING, pending_receiver, pending_receiver",
    "PROCESSING, pending_customer_info_update, pending_receiver",
    "NEEDS_INFO, pending_receiver, pending_customer_info_update",
    "NEEDS_INFO, pending_customer_info_update, pending_customer_info_update",
    "REJECTED, pending_receiver, error",
    "REJECTED, pending_customer_info_update, error",
  )
  fun test_handle_sep31_ok_with_id(customerStatus: String, oldStatus: String, newStatus: String) {
    val request =
      NotifyCustomerInfoUpdatedRequest.builder()
        .transactionId(TX_ID)
        .customerId(TEST_CUSTOMER_ID)
        .customerType(TEST_CUSTOMER_TYPE)
        .message(TEST_MESSAGE)
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.id = TX_ID
    txn31.status = oldStatus
    txn31.userActionRequiredBy = Instant.now()
    val sep31TxnCapture = slot<JdbcSep31Transaction>()
    val anchorEventCapture = mutableListOf<AnchorEvent>()

    val customer = GetCustomerResponse.builder().status(customerStatus).build()
    every {
      customerIntegration.getCustomer(
        GetCustomerRequest.builder()
          .transactionId(TX_ID)
          .id(TEST_CUSTOMER_ID)
          .type(TEST_CUSTOMER_TYPE)
          .build()
      )
    } returns customer
    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep31Txn = JdbcSep31Transaction()
    expectedSep31Txn.id = TX_ID
    expectedSep31Txn.status = newStatus
    expectedSep31Txn.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedSep31Txn.requiredInfoMessage = TEST_MESSAGE

    JSONAssert.assertEquals(
      gson.toJson(expectedSep31Txn),
      gson.toJson(sep31TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.id = TX_ID
    expectedResponse.sep = SEP_31
    expectedResponse.kind = RECEIVE
    expectedResponse.status = SepTransactionStatus.from(newStatus)
    expectedResponse.amountIn = Amount()
    expectedResponse.amountOut = Amount()
    expectedResponse.amountFee = Amount()
    expectedResponse.amountExpected = Amount()
    expectedResponse.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedResponse.message = TEST_MESSAGE
    expectedResponse.customers = Customers(StellarId(), StellarId())

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedCustomerEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture[0].id)
        .sep(SEP_12.sep.toString())
        .type(AnchorEvent.Type.CUSTOMER_UPDATED)
        .customer(GetCustomerResponse.to(customer))
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedCustomerEvent),
      gson.toJson(anchorEventCapture[0]),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture[1].id)
        .sep(SEP_31.sep.toString())
        .type(AnchorEvent.Type.TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture[1]),
      JSONCompareMode.STRICT
    )

    assertTrue(sep31TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep31TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep6_invalidRequest() {
    val request = NotifyCustomerInfoUpdatedRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_CUSTOMER_INFO_UPDATE.toString()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { requestValidator.validate(request) } throws
      InvalidParamsException(VALIDATION_ERROR_MESSAGE)

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(VALIDATION_ERROR_MESSAGE, ex.message?.trimIndent())

    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun test_handle_sep6_ok_without_id(kind: String) {
    val request = NotifyCustomerInfoUpdatedRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_CUSTOMER_INFO_UPDATE.toString()
    txn6.kind = kind
    txn6.userActionRequiredBy = Instant.now()
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep6") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep6Txn = JdbcSep6Transaction()
    expectedSep6Txn.status = PENDING_ANCHOR.toString()
    expectedSep6Txn.kind = kind
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_ANCHOR
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.amountExpected = Amount(null, "")
    expectedResponse.customers = Customers(StellarId(), StellarId())

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_6.sep.toString())
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

  @ParameterizedTest
  @CsvSource(
    "deposit, ACCEPTED, pending_anchor, pending_anchor",
    "deposit, ACCEPTED, pending_customer_info_update, pending_anchor",
    "deposit, PROCESSING, pending_anchor, pending_anchor",
    "deposit, PROCESSING, pending_customer_info_update, pending_anchor",
    "deposit, NEEDS_INFO, pending_anchor, pending_customer_info_update",
    "deposit, NEEDS_INFO, pending_customer_info_update, pending_customer_info_update",
    "deposit, REJECTED, pending_anchor, error",
    "deposit, REJECTED, pending_customer_info_update, error",
    "withdrawal, ACCEPTED, pending_anchor, pending_anchor",
    "withdrawal, ACCEPTED, pending_customer_info_update, pending_anchor",
    "withdrawal, PROCESSING, pending_anchor, pending_anchor",
    "withdrawal, PROCESSING, pending_customer_info_update, pending_anchor",
    "withdrawal, NEEDS_INFO, pending_anchor, pending_customer_info_update",
    "withdrawal, NEEDS_INFO, pending_customer_info_update, pending_customer_info_update",
    "withdrawal, REJECTED, pending_anchor, error",
    "withdrawal, REJECTED, pending_customer_info_update, error",
  )
  fun test_handle_sep6_ok_with_id(
    kind: String,
    customerStatus: String,
    oldStatus: String,
    newStatus: String
  ) {
    val request =
      NotifyCustomerInfoUpdatedRequest.builder()
        .transactionId(TX_ID)
        .customerId(TEST_CUSTOMER_ID)
        .customerType(TEST_CUSTOMER_TYPE)
        .message(TEST_MESSAGE)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.id = TX_ID
    txn6.status = oldStatus
    txn6.kind = kind
    txn6.userActionRequiredBy = Instant.now()
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = mutableListOf<AnchorEvent>()

    val customer = GetCustomerResponse.builder().status(customerStatus).build()
    every {
      customerIntegration.getCustomer(
        GetCustomerRequest.builder()
          .id(TEST_CUSTOMER_ID)
          .transactionId(TX_ID)
          .type(TEST_CUSTOMER_TYPE)
          .build()
      )
    } returns customer
    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep6") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep6Txn = JdbcSep6Transaction()
    expectedSep6Txn.id = TX_ID
    expectedSep6Txn.status = newStatus
    expectedSep6Txn.kind = kind
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.message = TEST_MESSAGE

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.id = TX_ID
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = SepTransactionStatus.from(newStatus)
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.amountExpected = Amount(null, "")
    expectedResponse.message = TEST_MESSAGE
    expectedResponse.customers = Customers(StellarId(), StellarId())

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedCustomerEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture[0].id)
        .sep(SEP_12.sep.toString())
        .type(AnchorEvent.Type.CUSTOMER_UPDATED)
        .customer(GetCustomerResponse.to(customer))
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedCustomerEvent),
      gson.toJson(anchorEventCapture[0]),
      JSONCompareMode.STRICT
    )

    val expectedTxnEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture[1].id)
        .sep(SEP_6.sep.toString())
        .type(AnchorEvent.Type.TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedTxnEvent),
      gson.toJson(anchorEventCapture[1]),
      JSONCompareMode.STRICT
    )

    assertTrue(sep6TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep6TxnCapture.captured.updatedAt <= endDate)
  }
}
