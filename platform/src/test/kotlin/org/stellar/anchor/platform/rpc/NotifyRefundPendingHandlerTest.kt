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
import org.stellar.anchor.api.event.AnchorEvent.Type.TRANSACTION_STATUS_CHANGED
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.*
import org.stellar.anchor.api.rpc.method.AmountAssetRequest
import org.stellar.anchor.api.rpc.method.NotifyRefundPendingRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.*
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24RefundPayment
import org.stellar.anchor.platform.data.JdbcSep24Refunds
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.service.AnchorMetrics.PLATFORM_RPC_TRANSACTION
import org.stellar.anchor.platform.utils.toRate
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils

class NotifyRefundPendingHandlerTest {
  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val FIAT_USD = "iso4217:USD"
    private const val STELLAR_USDC =
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val FIAT_USD_CODE = "USD"
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

  private lateinit var handler: NotifyRefundPendingHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    this.assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    this.handler =
      NotifyRefundPendingHandler(
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
    val request = NotifyRefundPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    val spyTxn24 = spyk(txn24)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns SEP_38.sep.toString()

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_refund_pending] is not supported. Status[pending_anchor], kind[null], protocol[38], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = NotifyRefundPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
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
  fun test_handle_invalidAmounts() {
    val request =
      NotifyRefundPendingRequest.builder()
        .transactionId(TX_ID)
        .refund(
          NotifyRefundPendingRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .id("1")
            .build()
        )
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.amountInAsset = STELLAR_USDC
    txn24.transferReceivedAt = Instant.now()
    txn24.kind = DEPOSIT.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    request.refund.amount.amount = "-1"
    var ex = assertThrows<BadRequestException> { handler.handle(request) }
    assertEquals("refund.amount.amount should be positive", ex.message)
    request.refund.amount.amount = "1"

    request.refund.amountFee.amount = "-0.1"
    ex = assertThrows { handler.handle(request) }
    assertEquals("refund.amountFee.amount should be non-negative", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidAssets() {
    val request =
      NotifyRefundPendingRequest.builder()
        .transactionId(TX_ID)
        .refund(
          NotifyRefundPendingRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .id("1")
            .build()
        )
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountFeeAsset = FIAT_USD
    txn24.transferReceivedAt = Instant.now()
    txn24.kind = DEPOSIT.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    request.refund.amount.asset = FIAT_USD
    var ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("refund.amount.asset does not match transaction amount_in_asset", ex.message)
    request.refund.amount.asset = STELLAR_USDC

    request.refund.amountFee.asset = STELLAR_USDC
    ex = assertThrows { handler.handle(request) }
    assertEquals(
      "refund.amount_fee.asset does not match match transaction amount_fee_asset",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedKind() {
    val request = NotifyRefundPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_refund_pending] is not supported. Status[pending_anchor], kind[withdrawal], protocol[24], funds received[true]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedStatus() {
    val request = NotifyRefundPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_refund_pending] is not supported. Status[incomplete], kind[deposit], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_ok_first_refund() {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyRefundPendingRequest.builder()
        .transactionId(TX_ID)
        .refund(
          NotifyRefundPendingRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0", FIAT_USD))
            .id("1")
            .build()
        )
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt
    txn24.amountInAsset = STELLAR_USDC
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountIn = "1"
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountFee = "0.1"
    txn24.amountFeeAsset = FIAT_USD
    txn24.userActionRequiredBy = Instant.now()

    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()
    val payment = JdbcSep24RefundPayment()
    payment.id = request.refund.id
    payment.idType = RefundPayment.IdType.EXTERNAL.toString()
    payment.amount = request.refund.amount.amount
    payment.fee = request.refund.amountFee.amount

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep24") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = DEPOSIT.kind
    expectedSep24Txn.status = PENDING_EXTERNAL.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = FIAT_USD
    expectedSep24Txn.transferReceivedAt = transferReceivedAt
    val expectedRefunds = JdbcSep24Refunds()
    expectedRefunds.amountRefunded = "1"
    expectedRefunds.amountFee = "0"
    expectedRefunds.payments = listOf(payment)
    expectedSep24Txn.refunds = expectedRefunds

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = DEPOSIT
    expectedResponse.status = PENDING_EXTERNAL
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", FIAT_USD).toRate()
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    val refundPayment = RefundPayment()
    refundPayment.amount = Amount("1", txn24.amountInAsset)
    refundPayment.fee = Amount("0", txn24.amountInAsset)
    refundPayment.id = request.refund.id
    refundPayment.idType = RefundPayment.IdType.STELLAR
    val refunded = Amount("1", txn24.amountInAsset)
    val refundedFee = Amount("0", txn24.amountInAsset)
    expectedResponse.refunds = Refunds(refunded, refundedFee, arrayOf(refundPayment))

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_24.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture.captured),
      JSONCompareMode.STRICT
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep24_ok_second_refund() {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyRefundPendingRequest.builder()
        .transactionId(TX_ID)
        .refund(
          NotifyRefundPendingRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .id("1")
            .build()
        )
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountIn = "2.2"
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountFee = "0.1"
    txn24.amountFeeAsset = FIAT_USD

    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()
    val payment = JdbcSep24RefundPayment()
    payment.id = request.refund.id
    payment.idType = RefundPayment.IdType.EXTERNAL.toString()
    payment.amount = request.refund.amount.amount
    payment.fee = request.refund.amountFee.amount
    val refunds = JdbcSep24Refunds()
    refunds.amountRefunded = "1"
    refunds.amountFee = "0.1"
    refunds.payments = listOf(payment)
    txn24.refunds = refunds

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep24") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = DEPOSIT.kind
    expectedSep24Txn.status = PENDING_EXTERNAL.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "2.2"
    expectedSep24Txn.amountInAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = FIAT_USD
    expectedSep24Txn.transferReceivedAt = transferReceivedAt
    val expectedRefunds = JdbcSep24Refunds()
    expectedRefunds.amountRefunded = "2.2"
    expectedRefunds.amountFee = "0.2"
    expectedRefunds.payments = listOf(payment, payment)
    expectedSep24Txn.refunds = expectedRefunds

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = DEPOSIT
    expectedResponse.status = PENDING_EXTERNAL
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.amountIn = Amount("2.2", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", FIAT_USD).toRate()
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    val refundPayment = RefundPayment()
    refundPayment.amount = Amount("1", txn24.amountInAsset)
    refundPayment.fee = Amount("0.1", txn24.amountInAsset)
    refundPayment.id = request.refund.id
    refundPayment.idType = RefundPayment.IdType.STELLAR
    val refunded = Amount("2.2", txn24.amountInAsset)
    val refundedFee = Amount("0.2", txn24.amountInAsset)
    expectedResponse.refunds = Refunds(refunded, refundedFee, arrayOf(refundPayment, refundPayment))

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_24.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture.captured),
      JSONCompareMode.STRICT
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep24_ok_withdrawal() {
    val transferReceivedAt = Instant.now()
    val request = NotifyRefundPendingRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_COMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = transferReceivedAt
    txn24.amountInAsset = STELLAR_USDC
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountIn = "1"
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountFee = "0.1"
    txn24.amountFeeAsset = FIAT_USD

    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep24") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_ANCHOR.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = FIAT_USD
    expectedSep24Txn.transferReceivedAt = transferReceivedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_ANCHOR
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", FIAT_USD).toRate()
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_24.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
        .transaction(expectedResponse)
        .build()

    JSONAssert.assertEquals(
      gson.toJson(expectedEvent),
      gson.toJson(anchorEventCapture.captured),
      JSONCompareMode.STRICT
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep24_more_then_amount_in() {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyRefundPendingRequest.builder()
        .transactionId(TX_ID)
        .refund(
          NotifyRefundPendingRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .id("1")
            .build()
        )
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountIn = "1"
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountFee = "0.1"
    txn24.amountFeeAsset = FIAT_USD

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(any()) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Refund amount exceeds amount_in", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_unsupportedKind(kind: String) {
    val request = NotifyRefundPendingRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = kind
    txn6.transferReceivedAt = Instant.now()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_refund_pending] is not supported. Status[pending_anchor], kind[$kind], protocol[6], funds received[true]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_unsupportedStatus(kind: String) {
    val request = NotifyRefundPendingRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_refund_pending] is not supported. Status[incomplete], kind[$kind], protocol[6], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_ok_first_refund(kind: String) {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyRefundPendingRequest.builder()
        .transactionId(TX_ID)
        .refund(
          NotifyRefundPendingRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0", FIAT_USD))
            .id("1")
            .build()
        )
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = kind
    txn6.transferReceivedAt = transferReceivedAt
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountIn = "1"
    txn6.amountInAsset = STELLAR_USDC
    txn6.amountFee = "0.1"
    txn6.amountFeeAsset = FIAT_USD

    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()
    val payment = RefundPayment()
    payment.id = request.refund.id
    payment.idType = RefundPayment.IdType.EXTERNAL
    payment.amount = Amount("1", STELLAR_USDC)
    payment.fee = Amount("0", FIAT_USD)

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
    expectedSep6Txn.kind = kind
    expectedSep6Txn.status = PENDING_EXTERNAL.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = STELLAR_USDC
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = FIAT_USD
    expectedSep6Txn.transferReceivedAt = transferReceivedAt
    val expectedRefunds = Refunds()
    expectedRefunds.amountRefunded = Amount("1", STELLAR_USDC)
    expectedRefunds.amountFee = Amount("0", FIAT_USD)
    expectedRefunds.payments = arrayOf(payment)
    expectedSep6Txn.refunds = expectedRefunds

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_EXTERNAL
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", FIAT_USD).toRate()
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.transferReceivedAt = transferReceivedAt
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    val refundPayment = RefundPayment()
    refundPayment.amount = Amount("1", txn6.amountInAsset)
    refundPayment.fee = Amount("0", txn6.amountFeeAsset)
    refundPayment.id = request.refund.id
    refundPayment.idType = RefundPayment.IdType.EXTERNAL
    val refunded = Amount("1", txn6.amountInAsset)
    val refundedFee = Amount("0", txn6.amountFeeAsset)
    expectedResponse.refunds = Refunds(refunded, refundedFee, arrayOf(refundPayment))

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_6.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
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

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_ok_second_refund(kind: String) {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyRefundPendingRequest.builder()
        .transactionId(TX_ID)
        .refund(
          NotifyRefundPendingRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .id("1")
            .build()
        )
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = kind
    txn6.transferReceivedAt = transferReceivedAt
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountIn = "2.2"
    txn6.amountInAsset = STELLAR_USDC
    txn6.amountFee = "0.1"
    txn6.amountFeeAsset = FIAT_USD

    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()
    val payment = RefundPayment()
    payment.id = request.refund.id
    payment.amount = Amount("1", STELLAR_USDC)
    payment.fee = Amount("0.1", FIAT_USD)
    val refunds = Refunds()
    payment.id = request.refund.id
    payment.idType = RefundPayment.IdType.EXTERNAL
    refunds.amountRefunded = Amount("1", STELLAR_USDC)
    refunds.amountFee = Amount("0.1", STELLAR_USDC)
    refunds.payments = arrayOf(payment)
    txn6.refunds = refunds

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
    expectedSep6Txn.kind = kind
    expectedSep6Txn.status = PENDING_EXTERNAL.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "2.2"
    expectedSep6Txn.amountInAsset = STELLAR_USDC
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = FIAT_USD
    expectedSep6Txn.transferReceivedAt = transferReceivedAt
    val expectedRefunds = Refunds()
    expectedRefunds.amountRefunded = Amount("2.2", STELLAR_USDC)
    expectedRefunds.amountFee = Amount("0.2", FIAT_USD)
    expectedRefunds.payments = arrayOf(payment, payment)
    expectedSep6Txn.refunds = expectedRefunds

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_EXTERNAL
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.amountIn = Amount("2.2", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", FIAT_USD).toRate()
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.transferReceivedAt = transferReceivedAt
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    val refundPayment = RefundPayment()
    refundPayment.amount = Amount("1", txn6.amountInAsset)
    refundPayment.fee = Amount("0.1", txn6.amountFeeAsset)
    refundPayment.id = request.refund.id
    refundPayment.idType = RefundPayment.IdType.EXTERNAL
    val refunded = Amount("2.2", txn6.amountInAsset)
    val refundedFee = Amount("0.2", txn6.amountFeeAsset)
    expectedResponse.refunds = Refunds(refunded, refundedFee, arrayOf(refundPayment, refundPayment))

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_6.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
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

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_ok_withdrawal(kind: String) {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyRefundPendingRequest.builder()
        .transactionId(TX_ID)
        .refund(
          NotifyRefundPendingRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0", FIAT_USD))
            .id("1")
            .build()
        )
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_USR_TRANSFER_COMPLETE.toString()
    txn6.kind = kind
    txn6.transferReceivedAt = transferReceivedAt
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountIn = "1"
    txn6.amountInAsset = STELLAR_USDC
    txn6.amountFee = "0.1"
    txn6.amountFeeAsset = FIAT_USD

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

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep6Txn = JdbcSep6Transaction()
    expectedSep6Txn.kind = kind
    expectedSep6Txn.status = PENDING_ANCHOR.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = STELLAR_USDC
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = FIAT_USD
    expectedSep6Txn.transferReceivedAt = transferReceivedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_ANCHOR
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", FIAT_USD).toRate()
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.transferReceivedAt = transferReceivedAt
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT
    )

    val expectedEvent =
      AnchorEvent.builder()
        .id(anchorEventCapture.captured.id)
        .sep(SEP_6.sep.toString())
        .type(TRANSACTION_STATUS_CHANGED)
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

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_more_then_amount_in(kind: String) {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyRefundPendingRequest.builder()
        .transactionId(TX_ID)
        .refund(
          NotifyRefundPendingRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .id("1")
            .build()
        )
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = DEPOSIT.kind
    txn6.transferReceivedAt = transferReceivedAt
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountIn = "1"
    txn6.amountInAsset = STELLAR_USDC
    txn6.amountFee = "0.1"
    txn6.amountFeeAsset = FIAT_USD

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(any()) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Refund amount exceeds amount_in", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }
}
