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
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.RECEIVE
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_31
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_38
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_6
import org.stellar.anchor.api.rpc.method.AmountAssetRequest
import org.stellar.anchor.api.rpc.method.DoStellarRefundRequest
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.SepTransactionStatus.INCOMPLETE
import org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_ANCHOR
import org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_RECEIVER
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.Customers
import org.stellar.anchor.api.shared.RefundPayment
import org.stellar.anchor.api.shared.Refunds
import org.stellar.anchor.api.shared.StellarId
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.config.CustodyConfig
import org.stellar.anchor.custody.CustodyService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24RefundPayment
import org.stellar.anchor.platform.data.JdbcSep24Refunds
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep31RefundPayment
import org.stellar.anchor.platform.data.JdbcSep31Refunds
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.service.AnchorMetrics.PLATFORM_RPC_TRANSACTION
import org.stellar.anchor.platform.utils.toRate
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils

class DoStellarRefundHandlerTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val FIAT_USD = "iso4217:USD"
    private const val STELLAR_USDC =
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val FIAT_USD_CODE = "USD"
    private const val MEMO = "testMemo"
    private const val MEMO_TYPE = "text"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"
  }

  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var custodyConfig: CustodyConfig

  @MockK(relaxed = true) private lateinit var custodyService: CustodyService

  @MockK(relaxed = true) private lateinit var eventService: EventService

  @MockK(relaxed = true) private lateinit var metricsService: MetricsService

  @MockK(relaxed = true) private lateinit var eventSession: Session

  @MockK(relaxed = true) private lateinit var sepTransactionCounter: Counter

  private lateinit var handler: DoStellarRefundHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    this.assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    this.handler =
      DoStellarRefundHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        custodyConfig,
        assetService,
        custodyService,
        eventService,
        metricsService
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = DoStellarRefundRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    val spyTxn24 = spyk(txn24)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns SEP_38.sep.toString()

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[do_stellar_refund] is not supported. Status[pending_anchor], kind[null], protocol[38], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = DoStellarRefundRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.transferReceivedAt = Instant.now()
    txn24.kind = WITHDRAWAL.kind

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
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .build()
        )
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountOutAsset = STELLAR_USDC
    txn24.amountFeeAsset = FIAT_USD
    txn24.transferReceivedAt = Instant.now()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

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
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .build()
        )
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountOutAsset = STELLAR_USDC
    txn24.amountFeeAsset = FIAT_USD
    txn24.transferReceivedAt = Instant.now()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

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
    val request = DoStellarRefundRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[do_stellar_refund] is not supported. Status[incomplete], kind[deposit], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedStatus() {
    val request = DoStellarRefundRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[do_stellar_refund] is not supported. Status[incomplete], kind[withdrawal], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_disabledCustodyIntegration() {
    val request = DoStellarRefundRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountOutAsset = STELLAR_USDC
    txn24.amountFeeAsset = FIAT_USD
    txn24.transferReceivedAt = Instant.now()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("RPC method[do_stellar_refund] requires enabled custody integration", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_sent_more_then_amount_in() {
    val transferReceivedAt = Instant.now()
    val request =
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
            .build()
        )
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = transferReceivedAt
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountIn = "1.1"
    txn24.amountOutAsset = FIAT_USD
    txn24.amountOut = "1"
    txn24.amountFeeAsset = STELLAR_USDC
    txn24.amountFee = "0.1"
    txn24.refundMemo = MEMO
    txn24.refundMemoType = MEMO_TYPE

    val payment = JdbcSep24RefundPayment()
    payment.id = "1"
    payment.idType = RefundPayment.IdType.STELLAR.toString()
    payment.amount = "0.1"
    payment.fee = "0"
    val refunds = JdbcSep24Refunds()
    refunds.amountRefunded = "1"
    refunds.amountFee = "0.1"
    refunds.payments = listOf(payment)
    txn24.refunds = refunds

    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Refund amount exceeds amount_in", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_ok() {
    val transferReceivedAt = Instant.now()
    val request =
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .build()
        )
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = transferReceivedAt
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountIn = "1.1"
    txn24.amountOutAsset = STELLAR_USDC
    txn24.amountOut = "1"
    txn24.amountFeeAsset = FIAT_USD
    txn24.amountFee = "0.1"
    txn24.refundMemo = MEMO
    txn24.refundMemoType = MEMO_TYPE
    txn24.userActionRequiredBy = Instant.now()
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
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
    expectedSep24Txn.status = SepTransactionStatus.PENDING_STELLAR.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountInAsset = STELLAR_USDC
    expectedSep24Txn.amountIn = "1.1"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountOut = "1"
    expectedSep24Txn.amountFeeAsset = FIAT_USD
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.refundMemo = MEMO
    expectedSep24Txn.refundMemoType = MEMO_TYPE
    expectedSep24Txn.transferReceivedAt = transferReceivedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = SepTransactionStatus.PENDING_STELLAR
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.amountIn = Amount("1.1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("1", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", FIAT_USD).toRate()
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.refundMemo = MEMO
    expectedResponse.refundMemoType = MEMO_TYPE

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
  fun test_handle_sep31_ok() {
    val transferReceivedAt = Instant.now()
    val request =
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
            .build()
        )
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    txn31.transferReceivedAt = transferReceivedAt
    txn31.amountInAsset = STELLAR_USDC
    txn31.amountIn = "1.1"
    txn31.amountOutAsset = FIAT_USD
    txn31.amountOut = "1"
    txn31.amountFeeAsset = STELLAR_USDC
    txn31.amountFee = "0.1"
    val sep31TxnCapture = slot<JdbcSep31Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn24Store.save(any()) }

    val expectedSep31Txn = JdbcSep31Transaction()
    expectedSep31Txn.status = SepTransactionStatus.PENDING_STELLAR.toString()
    expectedSep31Txn.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedSep31Txn.amountInAsset = STELLAR_USDC
    expectedSep31Txn.amountIn = "1.1"
    expectedSep31Txn.amountOutAsset = FIAT_USD
    expectedSep31Txn.amountOut = "1"
    expectedSep31Txn.amountFeeAsset = STELLAR_USDC
    expectedSep31Txn.amountFee = "0.1"
    expectedSep31Txn.transferReceivedAt = transferReceivedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedSep31Txn),
      gson.toJson(sep31TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_31
    expectedResponse.kind = RECEIVE
    expectedResponse.status = SepTransactionStatus.PENDING_STELLAR
    expectedResponse.amountExpected = Amount(null, STELLAR_USDC)
    expectedResponse.amountIn = Amount("1.1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("1", FIAT_USD)
    expectedResponse.amountFee = Amount("0.1", STELLAR_USDC)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.updatedAt = sep31TxnCapture.captured.updatedAt
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
  fun test_handle_sep31_sent_more_then_amount_in() {
    val transferReceivedAt = Instant.now()
    val request =
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
            .build()
        )
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    txn31.transferReceivedAt = transferReceivedAt
    txn31.amountInAsset = STELLAR_USDC
    txn31.amountIn = "1"
    txn31.amountOutAsset = FIAT_USD
    txn31.amountOut = "1"
    txn31.amountFeeAsset = STELLAR_USDC
    txn31.amountFee = "0.1"
    val sep31TxnCapture = slot<JdbcSep31Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Refund amount exceeds amount_in", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_sent_less_then_amount_in() {
    val transferReceivedAt = Instant.now()
    val request =
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("0.8", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
            .build()
        )
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    txn31.transferReceivedAt = transferReceivedAt
    txn31.amountInAsset = STELLAR_USDC
    txn31.amountIn = "1"
    txn31.amountOutAsset = FIAT_USD
    txn31.amountOut = "1"
    txn31.amountFeeAsset = STELLAR_USDC
    txn31.amountFee = "0.1"
    val sep31TxnCapture = slot<JdbcSep31Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns null
    every { txn31Store.findByTransactionId(any()) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Refund amount is less than amount_in", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_sent_multiple_refund() {
    val transferReceivedAt = Instant.now()
    val request =
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("0.8", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
            .build()
        )
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    txn31.transferReceivedAt = transferReceivedAt
    txn31.amountInAsset = STELLAR_USDC
    txn31.amountIn = "1"
    txn31.amountOutAsset = FIAT_USD
    txn31.amountOut = "1"
    txn31.amountFeeAsset = STELLAR_USDC
    txn31.amountFee = "0.1"
    val payment = JdbcSep31RefundPayment()
    payment.id = "1"
    payment.amount = "0.1"
    payment.fee = "0.1"
    val refunds = JdbcSep31Refunds()
    refunds.amountRefunded = "0.1"
    refunds.amountFee = "0.1"
    refunds.payments = listOf(payment)
    txn31.refunds = refunds
    val sep31TxnCapture = slot<JdbcSep31Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "Multiple refunds aren't supported for kind[RECEIVE], protocol[31] and action[do_stellar_refund]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_unsupportedKind(kind: String) {
    val request = DoStellarRefundRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[do_stellar_refund] is not supported. Status[incomplete], kind[$kind], protocol[6], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_unsupportedStatus(kind: String) {
    val request = DoStellarRefundRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[do_stellar_refund] is not supported. Status[incomplete], kind[$kind], protocol[6], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_disabledCustodyIntegration(kind: String) {
    val request = DoStellarRefundRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountOutAsset = STELLAR_USDC
    txn6.amountFeeAsset = FIAT_USD
    txn6.transferReceivedAt = Instant.now()
    txn6.kind = kind
    val sep6TxnCapture = slot<JdbcSep6Transaction>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns false

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("RPC method[do_stellar_refund] requires enabled custody integration", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_sent_more_then_amount_in(kind: String) {
    val transferReceivedAt = Instant.now()
    val request =
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", STELLAR_USDC))
            .build()
        )
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = kind
    txn6.transferReceivedAt = transferReceivedAt
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountInAsset = STELLAR_USDC
    txn6.amountIn = "1.1"
    txn6.amountOutAsset = FIAT_USD
    txn6.amountOut = "1"
    txn6.amountFeeAsset = STELLAR_USDC
    txn6.amountFee = "0.1"
    txn6.refundMemo = MEMO
    txn6.refundMemoType = MEMO_TYPE

    val payment = RefundPayment()
    payment.id = "1"
    payment.amount = Amount("0.1", STELLAR_USDC)
    payment.fee = Amount("0", STELLAR_USDC)
    val refunds = Refunds()
    refunds.amountRefunded = Amount("1", STELLAR_USDC)
    refunds.amountFee = Amount("0.1", STELLAR_USDC)
    refunds.payments = arrayOf(payment)
    txn6.refunds = refunds

    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Refund amount exceeds amount_in", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_ok(kind: String) {
    val transferReceivedAt = Instant.now()
    val request =
      DoStellarRefundRequest.builder()
        .transactionId(TX_ID)
        .refund(
          DoStellarRefundRequest.Refund.builder()
            .amount(AmountAssetRequest("1", STELLAR_USDC))
            .amountFee(AmountAssetRequest("0.1", FIAT_USD))
            .build()
        )
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = kind
    txn6.transferReceivedAt = transferReceivedAt
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountInAsset = STELLAR_USDC
    txn6.amountIn = "1.1"
    txn6.amountOutAsset = STELLAR_USDC
    txn6.amountOut = "1"
    txn6.amountFeeAsset = FIAT_USD
    txn6.amountFee = "0.1"
    txn6.refundMemo = MEMO
    txn6.refundMemoType = MEMO_TYPE
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
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
    expectedSep6Txn.status = SepTransactionStatus.PENDING_STELLAR.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountInAsset = STELLAR_USDC
    expectedSep6Txn.amountIn = "1.1"
    expectedSep6Txn.amountOutAsset = STELLAR_USDC
    expectedSep6Txn.amountOut = "1"
    expectedSep6Txn.amountFeeAsset = FIAT_USD
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.refundMemo = MEMO
    expectedSep6Txn.refundMemoType = MEMO_TYPE
    expectedSep6Txn.transferReceivedAt = transferReceivedAt

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = SepTransactionStatus.PENDING_STELLAR
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.amountIn = Amount("1.1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("1", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", FIAT_USD).toRate()
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.transferReceivedAt = transferReceivedAt
    expectedResponse.refundMemo = MEMO
    expectedResponse.refundMemoType = MEMO_TYPE
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
}
