package org.stellar.anchor.platform.rpc

import com.google.gson.reflect.TypeToken
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
import org.stellar.anchor.api.exception.rpc.InternalErrorException
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.*
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.*
import org.stellar.anchor.api.rpc.method.AmountRequest
import org.stellar.anchor.api.rpc.method.NotifyOnchainFundsReceivedRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.*
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.horizon.Horizon
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.service.AnchorMetrics.PLATFORM_RPC_TRANSACTION
import org.stellar.anchor.platform.utils.toRate
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse

class NotifyOnchainFundsReceivedHandlerTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val FIAT_USD = "iso4217:USD"
    private const val STELLAR_USDC =
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val FIAT_USD_CODE = "USD"
    private const val STELLAR_TX_ID = "stellarTxId"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"
    private const val STELLAR_PAYMENT_DATE = "2023-05-10T10:18:20Z"
  }

  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var horizon: Horizon

  @MockK(relaxed = true) private lateinit var eventService: EventService

  @MockK(relaxed = true) private lateinit var metricsService: MetricsService

  @MockK(relaxed = true) private lateinit var eventSession: Session

  @MockK(relaxed = true) private lateinit var sepTransactionCounter: Counter

  private lateinit var handler: NotifyOnchainFundsReceivedHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    this.assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    this.handler =
      NotifyOnchainFundsReceivedHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        horizon,
        assetService,
        eventService,
        metricsService
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = NotifyOnchainFundsReceivedRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_START.toString()
    txn24.kind = WITHDRAWAL.kind
    val spyTxn24 = spyk(txn24)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns SEP_38.sep.toString()

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_received] is not supported. Status[pending_user_transfer_start], kind[null], protocol[38], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = NotifyOnchainFundsReceivedRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_START.toString()
    txn24.kind = WITHDRAWAL.kind
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
  fun test_handle_notAllAmounts() {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .amountIn(AmountRequest("1"))
        .amountOut(AmountRequest("1"))
        .transactionId(TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_START.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(
      "Invalid amounts combination provided: all, none or only amount_in should be set",
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
      NotifyOnchainFundsReceivedRequest.builder()
        .amountIn(AmountRequest("1"))
        .amountOut(AmountRequest("1"))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .transactionId(TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_START.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = FIAT_USD
    txn24.amountOutAsset = STELLAR_USDC
    txn24.amountFeeAsset = STELLAR_USDC
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    request.amountIn.amount = "-1"
    var ex = assertThrows<BadRequestException> { handler.handle(request) }
    assertEquals("amount_in.amount should be positive", ex.message)
    request.amountIn.amount = "1"

    request.amountOut.amount = "-1"
    ex = assertThrows { handler.handle(request) }
    assertEquals("amount_out.amount should be positive", ex.message)
    request.amountOut.amount = "1"

    request.feeDetails.total = "-1"
    ex = assertThrows { handler.handle(request) }
    assertEquals("fee_details.amount should be non-negative", ex.message)
    request.feeDetails.total = "1"

    request.amountIn.amount = "0"
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedStatus() {
    val request = NotifyOnchainFundsReceivedRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_received] is not supported. Status[incomplete], kind[withdrawal], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedKind() {
    val request = NotifyOnchainFundsReceivedRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_START.toString()
    txn24.kind = DEPOSIT.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_received] is not supported. Status[pending_user_transfer_start], kind[deposit], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_ok_sep24_withAmounts() {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountRequest("1"))
        .amountOut(AmountRequest("0.9"))
        .feeDetails(FeeDetails("0.1", STELLAR_USDC))
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_START.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = FIAT_USD
    txn24.amountOutAsset = STELLAR_USDC
    txn24.amountFeeAsset = STELLAR_USDC
    txn24.userActionRequiredBy = Instant.now()
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PaymentOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val stellarTransactionsToken = object : TypeToken<List<StellarTransaction>>() {}.type
    val stellarTransactions: List<StellarTransaction> =
      gson.fromJson(stellarTransactions, stellarTransactionsToken)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(STELLAR_TX_ID) } returns operationRecords
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
    expectedSep24Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.stellarTransactionId = STELLAR_TX_ID
    expectedSep24Txn.stellarTransactions = stellarTransactions

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_ANCHOR
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.stellarTransactions = stellarTransactions
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

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
  fun test_handle_ok_sep24_onlyWithAmountIn() {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountRequest("1"))
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_START.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = FIAT_USD
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PaymentOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val stellarTransactionsToken = object : TypeToken<List<StellarTransaction>>() {}.type
    val stellarTransactions: List<StellarTransaction> =
      gson.fromJson(stellarTransactions, stellarTransactionsToken)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(STELLAR_TX_ID) } returns operationRecords
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
    expectedSep24Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = FIAT_USD
    expectedSep24Txn.stellarTransactionId = STELLAR_TX_ID
    expectedSep24Txn.stellarTransactions = stellarTransactions

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_ANCHOR
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.stellarTransactions = stellarTransactions
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

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
  fun test_handle_ok_sep24_withoutAmounts() {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_START.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PaymentOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val stellarTransactionsToken = object : TypeToken<List<StellarTransaction>>() {}.type
    val stellarTransactions: List<StellarTransaction> =
      gson.fromJson(stellarTransactions, stellarTransactionsToken)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(STELLAR_TX_ID) } returns operationRecords
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
    expectedSep24Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.stellarTransactionId = STELLAR_TX_ID
    expectedSep24Txn.stellarTransactions = stellarTransactions

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_ANCHOR
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.stellarTransactions = stellarTransactions
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

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
  fun test_handle_ok_sep24_withoutAmounts_invalidStellarTransaction() {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_USR_TRANSFER_START.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = FIAT_USD_CODE
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(any()) } throws
      NetworkException(400, "Invalid stellar transaction")

    val ex = assertThrows<InternalErrorException> { handler.handle(request) }
    assertEquals("Failed to retrieve Stellar transaction by ID[stellarTxId]", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_ok_sep31_withoutAmounts() {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_SENDER.toString()
    val sep31TxnCapture = slot<JdbcSep31Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PaymentOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val stellarTransactionsToken = object : TypeToken<List<StellarTransaction>>() {}.type
    val stellarTransactions: List<StellarTransaction> =
      gson.fromJson(stellarTransactions, stellarTransactionsToken)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(STELLAR_TX_ID) } returns operationRecords
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
    expectedSep31Txn.status = PENDING_RECEIVER.toString()
    expectedSep31Txn.fromAccount = operationRecords.get(0).sourceAccount
    expectedSep31Txn.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedSep31Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep31Txn.stellarTransactionId = STELLAR_TX_ID
    expectedSep31Txn.stellarTransactions = stellarTransactions

    JSONAssert.assertEquals(
      gson.toJson(expectedSep31Txn),
      gson.toJson(sep31TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_31
    expectedResponse.kind = RECEIVE
    expectedResponse.status = PENDING_RECEIVER
    expectedResponse.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedResponse.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedResponse.amountIn = Amount()
    expectedResponse.amountOut = Amount()
    expectedResponse.amountExpected = Amount()
    expectedResponse.sourceAccount = operationRecords.get(0).sourceAccount
    expectedResponse.stellarTransactions = stellarTransactions
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
        .type(TRANSACTION_STATUS_CHANGED)
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

  @CsvSource(value = ["deposit", "deposit-exchange", "withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_unsupportedStatus(kind: String) {
    val request = NotifyOnchainFundsReceivedRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_received] is not supported. Status[incomplete], kind[$kind], protocol[6], funds received[false]",
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
    val request = NotifyOnchainFundsReceivedRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_USR_TRANSFER_START.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_received] is not supported. Status[pending_user_transfer_start], kind[$kind], protocol[6], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_ok_sep6_withAmounts(kind: String) {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountRequest("1"))
        .amountOut(AmountRequest("0.9"))
        .feeDetails(FeeDetails("0.1", STELLAR_USDC))
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_USR_TRANSFER_START.toString()
    txn6.kind = kind
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountInAsset = FIAT_USD
    txn6.amountOutAsset = STELLAR_USDC
    txn6.amountFeeAsset = STELLAR_USDC
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PaymentOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val stellarTransactionsToken = object : TypeToken<List<StellarTransaction>>() {}.type
    val stellarTransactions: List<StellarTransaction> =
      gson.fromJson(stellarTransactions, stellarTransactionsToken)

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(STELLAR_TX_ID) } returns operationRecords
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
    expectedSep6Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = FIAT_USD
    expectedSep6Txn.amountOut = "0.9"
    expectedSep6Txn.amountOutAsset = STELLAR_USDC
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = STELLAR_USDC
    expectedSep6Txn.stellarTransactionId = STELLAR_TX_ID
    expectedSep6Txn.stellarTransactions = stellarTransactions

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
    expectedResponse.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountOut = Amount("0.9", STELLAR_USDC)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.stellarTransactions = stellarTransactions
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

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
  fun test_handle_ok_sep6_onlyWithAmountIn(kind: String) {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountRequest("1"))
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_USR_TRANSFER_START.toString()
    txn6.kind = kind
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountInAsset = FIAT_USD
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PaymentOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val stellarTransactionsToken = object : TypeToken<List<StellarTransaction>>() {}.type
    val stellarTransactions: List<StellarTransaction> =
      gson.fromJson(stellarTransactions, stellarTransactionsToken)

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(STELLAR_TX_ID) } returns operationRecords
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
    expectedSep6Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = FIAT_USD
    expectedSep6Txn.stellarTransactionId = STELLAR_TX_ID
    expectedSep6Txn.stellarTransactions = stellarTransactions

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
    expectedResponse.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedResponse.amountIn = Amount("1", FIAT_USD)
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.stellarTransactions = stellarTransactions
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

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
  fun test_handle_ok_sep6_withoutAmounts(kind: String) {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_USR_TRANSFER_START.toString()
    txn6.kind = kind
    txn6.requestAssetCode = FIAT_USD_CODE
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    val operationRecordsTypeToken =
      object : TypeToken<ArrayList<PaymentOperationResponse>>() {}.type
    val operationRecords: ArrayList<OperationResponse> =
      gson.fromJson(paymentOperationRecord, operationRecordsTypeToken)

    val stellarTransactionsToken = object : TypeToken<List<StellarTransaction>>() {}.type
    val stellarTransactions: List<StellarTransaction> =
      gson.fromJson(stellarTransactions, stellarTransactionsToken)

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(STELLAR_TX_ID) } returns operationRecords
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
    expectedSep6Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.stellarTransactionId = STELLAR_TX_ID
    expectedSep6Txn.stellarTransactions = stellarTransactions

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
    expectedResponse.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedResponse.amountExpected = Amount(null, FIAT_USD)
    expectedResponse.stellarTransactions = stellarTransactions
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

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
  fun test_handle_ok_sep6_withoutAmounts_invalidStellarTransaction(kind: String) {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_USR_TRANSFER_START.toString()
    txn6.kind = kind
    txn6.requestAssetCode = FIAT_USD_CODE
    val sep6TxnCapture = slot<JdbcSep6Transaction>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { horizon.getStellarTxnOperations(any()) } throws
      NetworkException(400, "Invalid stellar transaction")

    val ex = assertThrows<InternalErrorException> { handler.handle(request) }
    assertEquals("Failed to retrieve Stellar transaction by ID[stellarTxId]", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  private val paymentOperationRecord =
    """
[
  {
    "amount": "15.0000000",
    "asset_type": "native",
    "from": "testFrom",
    "to": "testTo",
    "id": 12345,
    "source_account": "testSourceAccount",
    "paging_token": "testPagingToken",
    "created_at": "2023-05-10T10:18:20Z",
    "transaction_hash": "testTxHash",
    "transaction_successful": true,
    "type": "payment",
    "links": {
      "effects": {
        "href": "https://horizon-testnet.stellar.org/operations/12345/effects",
        "templated": false
      },
      "precedes": {
        "href": "https://horizon-testnet.stellar.org/effects?order\u003dasc\u0026cursor\u003d12345",
        "templated": false
      },
      "self": {
        "href": "https://horizon-testnet.stellar.org/operations/12345",
        "templated": false
      },
      "succeeds": {
        "href": "https://horizon-testnet.stellar.org/effects?order\u003ddesc\u0026cursor\u003d12345",
        "templated": false
      },
      "transaction": {
        "href": "https://horizon-testnet.stellar.org/transactions/testTxHash",
        "templated": false
      }
    },
    "transaction": {
      "hash": "testTxHash",
      "memo": "12345",
      "memo_type": "id",
      "ledger": 1234,
      "created_at": "2023-05-10T10:18:20Z",
      "source_account": "testSourceAccount",
      "fee_account": "testFeeAccount",
      "successful": true,
      "paging_token": "1234",
      "source_accountSequence": 12345,
      "maxFee": 100,
      "fee_charged": 100,
      "operation_count": 1,
      "envelope_xdr": "testEnvelopeXdr",
      "result_xdr": "testResultXdr",
      "result_meta_xdr": "resultMetaXdr",
      "signatures": [
        "testSignature1"
      ],
      "preconditions": {
        "time_bounds": {
          "min_time": 0,
          "max_time": 1683713997
        },
        "min_accountSequenceAge": 0,
        "min_accountSequenceLedgerGap": 0
      },
      "links": {
        "account": {
          "href": "https://horizon-testnet.stellar.org/accounts/testAccount",
          "templated": false
        },
        "effects": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash/effects{?cursor,limit,order}",
          "templated": true
        },
        "ledger": {
          "href": "https://horizon-testnet.stellar.org/ledgers/1234",
          "templated": false
        },
        "operations": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash/operations{?cursor,limit,order}",
          "templated": true
        },
        "precedes": {
          "href": "https://horizon-testnet.stellar.org/transactions?order\u003dasc\u0026cursor\u003d12345",
          "templated": false
        },
        "self": {
          "href": "https://horizon-testnet.stellar.org/transactions/testTxHash",
          "templated": false
        },
        "succeeds": {
          "href": "https://horizon-testnet.stellar.org/transactions?order\u003ddesc\u0026cursor\u003d12345",
          "templated": false
        }
      },
      "rate_limitLimit": 0,
      "rate_limitRemaining": 0,
      "rate_limitReset": 0
    },
    "rate_limit_limit": 0,
    "rate_limit_remaining": 0,
    "rate_limit_lreset": 0
  }
]  
"""

  private val stellarTransactions =
    """
[
  {
    "id": "stellarTxId",
    "created_at": "2023-05-10T10:18:20Z",
    "envelope": "testEnvelopeXdr",
    "payments": [
      {
        "id": "12345",
        "amount": {
          "amount": "15.0000000",
          "asset": "native"
        },
        "asset_type": "native",
        "payment_type": "payment",
        "source_account": "testFrom",
        "destination_account": "testTo"
      }
    ]
  }
]  
"""
}
