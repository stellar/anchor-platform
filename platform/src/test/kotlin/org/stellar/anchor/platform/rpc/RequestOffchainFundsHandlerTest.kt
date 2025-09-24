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
import org.stellar.anchor.api.rpc.method.AmountRequest
import org.stellar.anchor.api.rpc.method.RequestOffchainFundsRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.*
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.service.AnchorMetrics.PLATFORM_RPC_TRANSACTION
import org.stellar.anchor.platform.utils.toRate
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils

class RequestOffchainFundsHandlerTest {

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

  private lateinit var handler: RequestOffchainFundsHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    this.assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    this.handler =
      RequestOffchainFundsHandler(
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
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    val spyTxn24 = spyk(txn24)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns SEP_38.sep.toString()

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_offchain_funds] is not supported. Status[incomplete], kind[null], protocol[38], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_unsupportedStatus() {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_EXTERNAL.toString()
    txn24.kind = DEPOSIT.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_offchain_funds] is not supported. Status[pending_external], kind[deposit], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind

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
  fun test_handle_withoutAmounts_amountsAbsent() {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("amount_in is required", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_withoutAmounts_fee_absent() {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    txn24.amountIn = "1"
    txn24.amountInAsset = FIAT_USD
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("fee_details or amount_fee is required", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_withoutAmounts_amount_fee_absent() {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    txn24.amountIn = "1"
    txn24.amountInAsset = FIAT_USD
    txn24.amountOut = "0.9"
    txn24.amountOutAsset = STELLAR_USDC
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("fee_details or amount_fee is required", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_notAllAmounts() {
    val request =
      RequestOffchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .transactionId(TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(
      "All (amount_out is optional) or none of the amount_in, amount_out, and (fee_details or amount_fee) should be set",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidAmounts() {
    val request =
      RequestOffchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", STELLAR_USDC))
        .feeDetails(FeeDetails("1", FIAT_USD, null))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    request.feeDetails.total = "-1"
    var ex = assertThrows<BadRequestException> { handler.handle(request) }
    assertEquals("fee_details.amount should be non-negative", ex.message)
    request.feeDetails.total = "1"

    request.amountExpected.amount = "-1"
    ex = assertThrows { handler.handle(request) }
    assertEquals("amount_expected.amount should be positive", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidAssets() {
    val request =
      RequestOffchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", STELLAR_USDC))
        .feeDetails(FeeDetails("1", FIAT_USD, null))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    request.amountIn.asset = STELLAR_USDC
    var ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("amount_in.asset should be non-stellar asset", ex.message)
    request.amountIn.asset = FIAT_USD

    request.amountOut.asset = FIAT_USD
    ex = assertThrows { handler.handle(request) }
    assertEquals("amount_out.asset should be stellar asset", ex.message)
    request.amountOut.asset = STELLAR_USDC

    request.feeDetails.asset = STELLAR_USDC
    ex = assertThrows { handler.handle(request) }
    assertEquals("fee_details.asset should be non-stellar asset", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_transferReceived() {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_offchain_funds] is not supported. Status[pending_anchor], kind[deposit], protocol[24], funds received[true]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedKind() {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_offchain_funds] is not supported. Status[incomplete], kind[withdrawal], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_with_quote_amount_out_missing() {
    val request =
      RequestOffchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(Amount("0.1", FIAT_USD).toRate())
        .transactionId(TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    txn24.quoteId = "testQuoteId"
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("amount_out is required for transactions with firm quotes", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_with_simple_quote() {
    val request =
      RequestOffchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(Amount("0.1", FIAT_USD).toRate())
        .transactionId(TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountOutAsset = STELLAR_USDC
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("amount_out is required for non-exchange transactions", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_ok_withExpectedAmount() {
    val request =
      RequestOffchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("0.9", STELLAR_USDC))
        .feeDetails(FeeDetails("0.1", FIAT_USD))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.userActionRequiredBy = Instant.now()
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
    expectedSep24Txn.kind = DEPOSIT.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = FIAT_USD
    expectedSep24Txn.amountExpected = "1"

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = DEPOSIT
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = FeeDetails("0.1", FIAT_USD, null)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
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
  fun test_handle_sep24_ok_withUserActionRequiredBy() {
    val actionRequiredBy = Instant.now().plusSeconds(100)
    val request =
      RequestOffchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("0.9", STELLAR_USDC))
        .feeDetails(FeeDetails("0.1", FIAT_USD, null))
        .userActionRequiredBy(actionRequiredBy)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

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
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = FIAT_USD
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.userActionRequiredBy = actionRequiredBy

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = DEPOSIT
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = FeeDetails("0.1", FIAT_USD, null)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.userActionRequiredBy = actionRequiredBy

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
  fun test_handle_sep24_ok_withoutAmounts_amountsPresent() {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountIn = "1"
    txn24.amountInAsset = FIAT_USD
    txn24.amountOut = "0.9"
    txn24.amountOutAsset = STELLAR_USDC
    txn24.amountFee = "0.1"
    txn24.amountFeeAsset = STELLAR_USDC
    txn24.amountExpected = "1"
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
    expectedSep24Txn.kind = DEPOSIT.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = DEPOSIT
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", STELLAR_USDC)
    expectedResponse.feeDetails = FeeDetails("0.1", STELLAR_USDC, null)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
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
  fun test_handle_sep6_transferReceived() {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = DEPOSIT.kind
    txn6.transferReceivedAt = Instant.now()

    every { txn6Store.findByTransactionId(any()) } returns txn6
    every { txn24Store.findByTransactionId(TX_ID) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_offchain_funds] is not supported. Status[pending_anchor], kind[deposit], protocol[6], funds received[true]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_unsupportedKind(kind: String) {
    val request = RequestOffchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_offchain_funds] is not supported. Status[incomplete], kind[$kind], protocol[6], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "deposit-exchange"])
  fun test_handle_sep6_with_quote_amount_out_missing(kind: String) {
    val request =
      RequestOffchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(Amount("0.1", FIAT_USD).toRate())
        .transactionId(TX_ID)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind
    txn6.quoteId = "testQuoteId"
    val sep6TxnCapture = slot<JdbcSep6Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns txn6
    every { txn24Store.findByTransactionId(TX_ID) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("amount_out is required for transactions with firm quotes", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "deposit-exchange"])
  fun test_handle_sep6_with_simple_quote(kind: String) {
    val request =
      RequestOffchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(Amount("0.1", FIAT_USD).toRate())
        .transactionId(TX_ID)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind
    txn6.amountInAsset = FIAT_USD
    txn6.amountOutAsset = FIAT_USD
    val sep6TxnCapture = slot<JdbcSep6Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns txn6
    every { txn24Store.findByTransactionId(TX_ID) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("amount_out is required for non-exchange transactions", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(
    value =
      [
        "deposit, incomplete",
        "deposit, pending_anchor",
        "deposit, pending_customer_info_update",
        "deposit-exchange, incomplete",
        "deposit-exchange, pending_anchor",
        "deposit-exchange, pending_customer_info_update"
      ]
  )
  @ParameterizedTest
  fun test_handle_sep6_ok_withExpectedAmount(kind: String, status: String) {
    val request =
      RequestOffchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("0.9", STELLAR_USDC))
        .feeDetails(FeeDetails("0.1", FIAT_USD, null))
        .amountExpected(AmountRequest("1"))
        .instructions(mapOf("first_name" to InstructionField.builder().build()))
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = status
    txn6.kind = kind
    txn6.requestAssetCode = FIAT_USD_CODE
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
    expectedSep6Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = FIAT_USD
    expectedSep6Txn.amountOut = "0.9"
    expectedSep6Txn.amountOutAsset = STELLAR_USDC
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = FIAT_USD
    expectedSep6Txn.amountExpected = "1"
    expectedSep6Txn.instructions = mapOf("first_name" to InstructionField.builder().build())

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = FeeDetails("0.1", FIAT_USD, null)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.instructions = mapOf("first_name" to InstructionField.builder().build())

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

  @CsvSource(
    value =
      [
        "deposit, incomplete",
        "deposit, pending_anchor",
        "deposit, pending_customer_info_update",
        "deposit-exchange, incomplete",
        "deposit-exchange, pending_anchor",
        "deposit-exchange, pending_customer_info_update"
      ]
  )
  @ParameterizedTest
  fun test_handle_sep6_ok_withoutAmountExpected(kind: String, status: String) {
    val request =
      RequestOffchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("0.9", STELLAR_USDC))
        .feeDetails(FeeDetails("0.1", FIAT_USD, null))
        .instructions(mapOf("first_name" to InstructionField.builder().build()))
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = status
    txn6.kind = kind
    txn6.requestAssetCode = FIAT_USD_CODE
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

    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep6Txn = JdbcSep6Transaction()
    expectedSep6Txn.kind = kind
    expectedSep6Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = FIAT_USD
    expectedSep6Txn.amountOut = "0.9"
    expectedSep6Txn.amountOutAsset = STELLAR_USDC
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = FIAT_USD
    expectedSep6Txn.amountExpected = "1"
    expectedSep6Txn.instructions = mapOf("first_name" to InstructionField.builder().build())

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", FIAT_USD)
    expectedResponse.feeDetails = FeeDetails("0.1", FIAT_USD, null)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.instructions = mapOf("first_name" to InstructionField.builder().build())

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

  @CsvSource(
    value =
      [
        "deposit, incomplete",
        "deposit, pending_anchor",
        "deposit, pending_customer_info_update",
        "deposit-exchange, incomplete",
        "deposit-exchange, pending_anchor",
        "deposit-exchange, pending_customer_info_update"
      ]
  )
  @ParameterizedTest
  fun test_handle_sep6_ok_withoutAmounts_amountsPresent(kind: String, status: String) {
    val request =
      RequestOffchainFundsRequest.builder()
        .transactionId(TX_ID)
        .instructions(mapOf("first_name" to InstructionField.builder().build()))
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = status
    txn6.kind = kind
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountIn = "1"
    txn6.amountInAsset = FIAT_USD
    txn6.amountOut = "0.9"
    txn6.amountOutAsset = STELLAR_USDC
    txn6.amountFee = "0.1"
    txn6.amountFeeAsset = STELLAR_USDC
    txn6.amountExpected = "1"
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
    expectedSep6Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = FIAT_USD
    expectedSep6Txn.amountOut = "0.9"
    expectedSep6Txn.amountOutAsset = STELLAR_USDC
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = STELLAR_USDC
    expectedSep6Txn.amountExpected = "1"
    expectedSep6Txn.instructions = mapOf("first_name" to InstructionField.builder().build())

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.amountFee = Amount("0.1", STELLAR_USDC)
    expectedResponse.feeDetails = FeeDetails("0.1", STELLAR_USDC, null)
    expectedResponse.amountExpected = Amount("1", FIAT_USD)
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.instructions = mapOf("first_name" to InstructionField.builder().build())

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
