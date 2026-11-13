package org.stellar.anchor.platform.rpc

import com.google.gson.reflect.TypeToken
import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
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
import org.stellar.anchor.api.exception.LedgerException
import org.stellar.anchor.api.exception.NotFoundException
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
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPathPaymentOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation
import org.stellar.anchor.ledger.PaymentTransferEvent
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep24TransactionStore
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep31TransactionStore
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.data.JdbcSep6TransactionStore
import org.stellar.anchor.platform.data.JdbcSepTransaction
import org.stellar.anchor.platform.observer.stellar.DefaultPaymentListener
import org.stellar.anchor.platform.observer.stellar.SacToAssetMapper
import org.stellar.anchor.platform.service.AnchorMetrics.PLATFORM_RPC_TRANSACTION
import org.stellar.anchor.platform.utils.toRate
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.MemoHelper
import org.stellar.sdk.Memo
import org.stellar.sdk.xdr.Asset
import org.stellar.sdk.xdr.AssetType
import org.stellar.sdk.xdr.OperationType

class NotifyOnchainFundsReceivedHandlerTest {

  companion object {
    private const val TX_ID = "testId"
    private const val FIAT_USD = "iso4217:USD"
    private const val STELLAR_USDC =
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val FIAT_USD_CODE = "USD"
    private const val STELLAR_TX_ID = "stellarTxId"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"
    private const val STELLAR_PAYMENT_DATE = "2023-05-10T10:18:20Z"
    private const val WITHDRAW_ACCOUNT = "testTo"
    private const val LEDGER_MEMO = "12345"
    private const val USDC_CODE_ISSUER =
      "USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"

    private val gson = GsonUtils.getInstance()
    val stellarTransactions: List<StellarTransaction> =
      gson.fromJson(
        stellarTransactionRecord,
        object : TypeToken<List<StellarTransaction>>() {}.type
      )
    private val testLedgerTxn =
      LedgerTransaction.builder()
        .hash("stellarTxId")
        .ledger(1L)
        .applicationOrder(1)
        .memo(MemoHelper.toXdr(Memo.id(12345)))
        .sourceAccount("testFrom")
        .createdAt(Instant.parse("2023-05-10T10:18:20Z"))
        .fee(100)
        .envelopeXdr("testEnvelopeXdr")
        .operations(
          listOf(
            LedgerOperation.builder()
              .type(OperationType.PAYMENT)
              .paymentOperation(
                LedgerPaymentOperation.builder()
                  .id("12345")
                  .amount(BigInteger.valueOf(150000000))
                  .asset(org.stellar.sdk.Asset.create(USDC_CODE_ISSUER).toXdr())
                  .from("testFrom")
                  .to("testTo")
                  .build()
              )
              .build()
          )
        )
        .build()
  }

  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var ledgerClient: LedgerClient

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
        ledgerClient,
        assetService,
        eventService,
        metricsService,
        mockk<SacToAssetMapper>()
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
    txn24.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    txn24.memo = LEDGER_MEMO
    txn24.memoType = "id"
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountOutAsset = STELLAR_USDC
    txn24.amountFeeAsset = STELLAR_USDC
    txn24.userActionRequiredBy = Instant.now()
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns testLedgerTxn
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
    expectedSep24Txn.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    expectedSep24Txn.memo = LEDGER_MEMO
    expectedSep24Txn.memoType = "id"
    expectedSep24Txn.status = PENDING_ANCHOR.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = STELLAR_USDC
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
    expectedResponse.memo = LEDGER_MEMO
    expectedResponse.memoType = "id"
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
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
    txn24.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    txn24.memo = LEDGER_MEMO
    txn24.memoType = "id"
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = STELLAR_USDC
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns testLedgerTxn
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
    expectedSep24Txn.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    expectedSep24Txn.memo = LEDGER_MEMO
    expectedSep24Txn.memoType = "id"
    expectedSep24Txn.status = PENDING_ANCHOR.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = STELLAR_USDC
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
    expectedResponse.memo = LEDGER_MEMO
    expectedResponse.memoType = "id"
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
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
    txn24.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    txn24.memo = LEDGER_MEMO
    txn24.memoType = "id"
    txn24.requestAssetCode = FIAT_USD_CODE
    txn24.amountInAsset = STELLAR_USDC
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns testLedgerTxn
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
    expectedSep24Txn.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    expectedSep24Txn.memo = LEDGER_MEMO
    expectedSep24Txn.memoType = "id"
    expectedSep24Txn.status = PENDING_ANCHOR.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep24Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep24Txn.amountInAsset = STELLAR_USDC
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
    expectedResponse.memo = LEDGER_MEMO
    expectedResponse.memoType = "id"
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
    every { ledgerClient.getTransaction(any()) } throws
      LedgerException("Invalid stellar transaction")

    val ex = assertThrows<NotFoundException> { handler.handle(request) }
    assertEquals("Transaction (hash=stellarTxId) not found", ex.message)

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
    txn31.toAccount = "testTo"
    txn31.stellarMemo = LEDGER_MEMO
    txn31.stellarMemoType = "id"
    txn31.amountInAsset = STELLAR_USDC
    val sep31TxnCapture = slot<JdbcSep31Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns testLedgerTxn
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
    expectedSep31Txn.fromAccount = testLedgerTxn.sourceAccount
    expectedSep31Txn.toAccount = "testTo"
    expectedSep31Txn.stellarMemo = LEDGER_MEMO
    expectedSep31Txn.stellarMemoType = "id"
    expectedSep31Txn.amountInAsset = STELLAR_USDC
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
    expectedResponse.amountIn = Amount(null, STELLAR_USDC)
    expectedResponse.amountOut = Amount()
    expectedResponse.amountExpected = Amount(null, STELLAR_USDC)
    expectedResponse.memo = LEDGER_MEMO
    expectedResponse.memoType = "id"
    expectedResponse.refundMemo = LEDGER_MEMO
    expectedResponse.refundMemoType = "id"
    expectedResponse.sourceAccount = testLedgerTxn.sourceAccount
    expectedResponse.destinationAccount = "testTo"
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

  @Test
  fun `test_handle_sep31_path_payment_as_first_op_credits_correct_from_account`() {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_SENDER.toString()
    txn31.toAccount = "distAccount"
    txn31.stellarMemo = LEDGER_MEMO
    txn31.stellarMemoType = "id"
    txn31.amountInAsset = "stellar:native"
    val sep31TxnCapture = slot<JdbcSep31Transaction>()

    val twoOpLedgerTxn =
      LedgerTransaction.builder()
        .hash(STELLAR_TX_ID)
        .ledger(1L)
        .applicationOrder(1)
        .memo(MemoHelper.toXdr(Memo.id(12345)))
        .sourceAccount("senderFrom")
        .createdAt(Instant.parse(STELLAR_PAYMENT_DATE))
        .fee(100)
        .envelopeXdr("testEnvelopeXdr")
        .operations(
          listOf(
            LedgerOperation.builder()
              .type(OperationType.PATH_PAYMENT_STRICT_SEND)
              .pathPaymentOperation(
                LedgerPathPaymentOperation.builder()
                  .id("11111")
                  .type(OperationType.PATH_PAYMENT_STRICT_SEND)
                  .amount(BigInteger.ONE)
                  .asset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
                  .from("attackerFrom")
                  .to("someoneElse")
                  .build()
              )
              .build(),
            LedgerOperation.builder()
              .type(OperationType.PAYMENT)
              .paymentOperation(
                LedgerPaymentOperation.builder()
                  .id("22222")
                  .amount(BigInteger.valueOf(150000000))
                  .asset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
                  .from("senderFrom")
                  .to("distAccount")
                  .build()
              )
              .build()
          )
        )
        .build()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns twoOpLedgerTxn
    every { eventSession.publish(any()) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    handler.handle(request)

    assertEquals("senderFrom", sep31TxnCapture.captured.fromAccount)
    assertEquals(PENDING_RECEIVER.toString(), sep31TxnCapture.captured.status)
    verify(exactly = 1) { txn31Store.save(any()) }
  }

  @Test
  fun `test_handle_sep31_paying_op_not_at_index_0_credits_correct_from_account`() {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_SENDER.toString()
    txn31.toAccount = "distAccount"
    txn31.stellarMemo = LEDGER_MEMO
    txn31.stellarMemoType = "id"
    txn31.amountInAsset = "stellar:native"
    val sep31TxnCapture = slot<JdbcSep31Transaction>()

    val twoOpLedgerTxn =
      LedgerTransaction.builder()
        .hash(STELLAR_TX_ID)
        .ledger(1L)
        .applicationOrder(1)
        .memo(MemoHelper.toXdr(Memo.id(12345)))
        .sourceAccount("sender")
        .createdAt(Instant.parse(STELLAR_PAYMENT_DATE))
        .fee(100)
        .envelopeXdr("testEnvelopeXdr")
        .operations(
          listOf(
            LedgerOperation.builder()
              .type(OperationType.PAYMENT)
              .paymentOperation(
                LedgerPaymentOperation.builder()
                  .id("11111")
                  .amount(BigInteger.ONE)
                  .asset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
                  .from("attacker")
                  .to("someoneElse")
                  .build()
              )
              .build(),
            LedgerOperation.builder()
              .type(OperationType.PAYMENT)
              .paymentOperation(
                LedgerPaymentOperation.builder()
                  .id("22222")
                  .amount(BigInteger.valueOf(150000000))
                  .asset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
                  .from("sender")
                  .to("distAccount")
                  .build()
              )
              .build()
          )
        )
        .build()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns twoOpLedgerTxn
    every { eventSession.publish(any()) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    handler.handle(request)

    assertEquals("sender", sep31TxnCapture.captured.fromAccount)
    assertEquals(PENDING_RECEIVER.toString(), sep31TxnCapture.captured.status)
    verify(exactly = 1) { txn31Store.save(any()) }
  }

  @Test
  fun `test_handle_sep31_path_payment_is_the_matching_op_credits_correct_from_account`() {
    val request =
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_SENDER.toString()
    txn31.toAccount = "distAccount"
    txn31.stellarMemo = LEDGER_MEMO
    txn31.stellarMemoType = "id"
    txn31.amountInAsset = "stellar:native"
    val sep31TxnCapture = slot<JdbcSep31Transaction>()

    val twoOpLedgerTxn =
      LedgerTransaction.builder()
        .hash(STELLAR_TX_ID)
        .ledger(1L)
        .applicationOrder(1)
        .memo(MemoHelper.toXdr(Memo.id(12345)))
        .sourceAccount("pathSender")
        .createdAt(Instant.parse(STELLAR_PAYMENT_DATE))
        .fee(100)
        .envelopeXdr("testEnvelopeXdr")
        .operations(
          listOf(
            LedgerOperation.builder()
              .type(OperationType.PAYMENT)
              .paymentOperation(
                LedgerPaymentOperation.builder()
                  .id("11111")
                  .amount(BigInteger.ONE)
                  .asset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
                  .from("decoy")
                  .to("someoneElse")
                  .build()
              )
              .build(),
            LedgerOperation.builder()
              .type(OperationType.PATH_PAYMENT_STRICT_SEND)
              .pathPaymentOperation(
                LedgerPathPaymentOperation.builder()
                  .id("22222")
                  .type(OperationType.PATH_PAYMENT_STRICT_SEND)
                  .amount(BigInteger.valueOf(150000000))
                  .asset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
                  .from("pathSender")
                  .to("distAccount")
                  .build()
              )
              .build()
          )
        )
        .build()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns twoOpLedgerTxn
    every { eventSession.publish(any()) } just Runs
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    handler.handle(request)

    assertEquals("pathSender", sep31TxnCapture.captured.fromAccount)
    assertEquals(PENDING_RECEIVER.toString(), sep31TxnCapture.captured.status)
    verify(exactly = 1) { txn31Store.save(any()) }
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
    txn6.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    txn6.memo = LEDGER_MEMO
    txn6.memoType = "id"
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountInAsset = STELLAR_USDC
    txn6.amountOutAsset = STELLAR_USDC
    txn6.amountFeeAsset = STELLAR_USDC
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns testLedgerTxn
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
    expectedSep6Txn.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    expectedSep6Txn.memo = LEDGER_MEMO
    expectedSep6Txn.memoType = "id"
    expectedSep6Txn.status = PENDING_ANCHOR.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = STELLAR_USDC
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
    expectedResponse.memo = LEDGER_MEMO
    expectedResponse.memoType = "id"
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
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
    txn6.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    txn6.memo = LEDGER_MEMO
    txn6.memoType = "id"
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountInAsset = STELLAR_USDC
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns testLedgerTxn
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
    expectedSep6Txn.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    expectedSep6Txn.memo = LEDGER_MEMO
    expectedSep6Txn.memoType = "id"
    expectedSep6Txn.status = PENDING_ANCHOR.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = STELLAR_USDC
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
    expectedResponse.memo = LEDGER_MEMO
    expectedResponse.memoType = "id"
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
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
    txn6.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    txn6.memo = LEDGER_MEMO
    txn6.memoType = "id"
    txn6.requestAssetCode = FIAT_USD_CODE
    txn6.amountInAsset = STELLAR_USDC
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns testLedgerTxn
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
    expectedSep6Txn.withdrawAnchorAccount = WITHDRAW_ACCOUNT
    expectedSep6Txn.memo = LEDGER_MEMO
    expectedSep6Txn.memoType = "id"
    expectedSep6Txn.status = PENDING_ANCHOR.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.transferReceivedAt = Instant.parse(STELLAR_PAYMENT_DATE)
    expectedSep6Txn.requestAssetCode = FIAT_USD_CODE
    expectedSep6Txn.amountInAsset = STELLAR_USDC
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
    expectedResponse.memo = LEDGER_MEMO
    expectedResponse.memoType = "id"
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
    every { ledgerClient.getTransaction(any()) } throws
      LedgerException("Invalid stellar transaction")

    val ex = assertThrows<NotFoundException> { handler.handle(request) }
    assertEquals("Transaction (hash=stellarTxId) not found", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  // ---- The hash must be a payment for this transaction (spec FGATE-01..11, HOLD-05) ----------

  private val usdcXdr = org.stellar.sdk.Asset.create(USDC_CODE_ISSUER).toXdr()
  private val eurcXdr =
    org.stellar.sdk.Asset.create("EURC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN")
      .toXdr()

  /** A transaction waiting for the user's payment, stored in the mocked stores. */
  private fun stubTransaction(
    protocol: String,
    account: String = WITHDRAW_ACCOUNT,
    status: String? = null
  ): JdbcSepTransaction {
    every { metricsService.counter(PLATFORM_RPC_TRANSACTION, any(), any()) } returns
      sepTransactionCounter
    every { eventSession.publish(any()) } just Runs
    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(any()) } returns null
    every { txn24Store.save(any()) } returns null
    every { txn31Store.save(any()) } returns null
    return when (protocol) {
      "24" ->
        JdbcSep24Transaction().also {
          it.id = TX_ID
          it.status = status ?: PENDING_USR_TRANSFER_START.toString()
          it.kind = WITHDRAWAL.kind
          it.withdrawAnchorAccount = account
          it.memo = LEDGER_MEMO
          it.memoType = "id"
          it.amountInAsset = STELLAR_USDC
          every { txn24Store.findByTransactionId(TX_ID) } returns it
        }
      "6" ->
        JdbcSep6Transaction().also {
          it.id = TX_ID
          it.status = status ?: PENDING_USR_TRANSFER_START.toString()
          it.kind = WITHDRAWAL.kind
          it.withdrawAnchorAccount = account
          it.memo = LEDGER_MEMO
          it.memoType = "id"
          it.amountInAsset = STELLAR_USDC
          every { txn6Store.findByTransactionId(TX_ID) } returns it
        }
      else ->
        JdbcSep31Transaction().also {
          it.id = TX_ID
          it.status = status ?: PENDING_SENDER.toString()
          it.toAccount = account
          it.stellarMemo = LEDGER_MEMO
          it.stellarMemoType = "id"
          it.amountInAsset = STELLAR_USDC
          every { txn31Store.findByTransactionId(TX_ID) } returns it
        }
    }
  }

  private fun payment(
    to: String = WITHDRAW_ACCOUNT,
    asset: Asset = usdcXdr,
    amount: Long = 150000000,
    from: String = "testFrom"
  ): LedgerOperation =
    LedgerOperation.builder()
      .type(OperationType.PAYMENT)
      .paymentOperation(
        LedgerPaymentOperation.builder()
          .id("12345")
          .amount(BigInteger.valueOf(amount))
          .asset(asset)
          .from(from)
          .to(to)
          .build()
      )
      .build()

  private fun ledgerOf(memo: Memo?, vararg operations: LedgerOperation): LedgerTransaction =
    LedgerTransaction.builder()
      .hash(STELLAR_TX_ID)
      .ledger(1L)
      .applicationOrder(1)
      .memo(memo?.let { MemoHelper.toXdr(it) })
      .sourceAccount("testFrom")
      .createdAt(Instant.parse(STELLAR_PAYMENT_DATE))
      .fee(100)
      .envelopeXdr("testEnvelopeXdr")
      .operations(operations.toList())
      .build()

  private fun hashRequest() =
    NotifyOnchainFundsReceivedRequest.builder()
      .transactionId(TX_ID)
      .stellarTransactionId(STELLAR_TX_ID)
      .build()

  private fun rejection(ledgerTxn: LedgerTransaction): InvalidParamsException {
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns ledgerTxn
    return assertThrows<InvalidParamsException> { handler.handle(hashRequest()) }
  }

  private fun accept(ledgerTxn: LedgerTransaction) {
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns ledgerTxn
    handler.handle(hashRequest())
  }

  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun test_handle_rejects_hash_without_payment_to_receiving_account(protocol: String) {
    stubTransaction(protocol)

    val ex = rejection(ledgerOf(Memo.id(12345), payment(to = "someoneElse")))

    assertContains(ex.message!!, "destination")
    assertContains(ex.message!!, STELLAR_TX_ID)
  }

  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun test_handle_rejects_hash_with_other_memo(protocol: String) {
    stubTransaction(protocol)

    val otherValue = rejection(ledgerOf(Memo.id(54321), payment()))

    assertContains(otherValue.message!!, "memo")
    assertContains(otherValue.message!!, STELLAR_TX_ID)
  }

  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun test_handle_rejects_hash_in_other_asset(protocol: String) {
    stubTransaction(protocol)

    val ex = rejection(ledgerOf(Memo.id(12345), payment(asset = eurcXdr)))

    assertContains(ex.message!!, "asset")
    assertContains(ex.message!!, STELLAR_TX_ID)
  }

  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun test_handle_rejects_matching_payment_with_zero_amount(protocol: String) {
    stubTransaction(protocol)

    val ex = rejection(ledgerOf(Memo.id(12345), payment(amount = 0)))

    assertContains(ex.message!!, "amount")
    assertContains(ex.message!!, STELLAR_TX_ID)
  }

  @Test
  fun test_handle_amount_boundary_zero_rejected_one_stroop_accepted() {
    val txn = stubTransaction("24")

    rejection(ledgerOf(Memo.id(12345), payment(amount = 0)))
    assertNull(txn.transferReceivedAt)

    accept(ledgerOf(Memo.id(12345), payment(amount = 1)))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertEquals(Instant.parse(STELLAR_PAYMENT_DATE), txn.transferReceivedAt)
  }

  @Test
  fun test_handle_sep31_rejects_hash_without_payment_to_toAccount() {
    val txn = stubTransaction("31") as JdbcSep31Transaction

    val ex = rejection(ledgerOf(Memo.id(12345), payment(to = "someoneElse")))

    assertContains(ex.message!!, "destination")
    assertNull(txn.fromAccount)
    assertNull(txn.transferReceivedAt)
    assertEquals(PENDING_SENDER.toString(), txn.status)
  }

  @Test
  fun test_handle_sep31_null_toAccount_rejects_the_hash() {
    val txn = stubTransaction("31") as JdbcSep31Transaction
    txn.toAccount = null

    val ex = rejection(ledgerOf(Memo.id(12345), payment(to = "someoneElse")))

    assertContains(ex.message!!, "destination")
    assertNull(txn.fromAccount)
    assertNull(txn.transferReceivedAt)
  }

  @Test
  fun test_handle_accepts_when_one_of_several_payments_matches() {
    val txn = stubTransaction("24")

    accept(
      ledgerOf(
        Memo.id(12345),
        payment(to = "someoneElse"),
        payment(asset = eurcXdr),
        payment(amount = 0),
        payment()
      )
    )

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertEquals(4, txn.stellarTransactions.single().payments.size)
  }

  @Test
  fun test_handle_accepts_muxed_destination_with_memo_id() {
    val account = "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    val muxed = org.stellar.sdk.MuxedAccount(account, BigInteger.valueOf(12345)).address

    val txn31 = stubTransaction("31", account)
    accept(ledgerOf(null, payment(to = muxed)))
    assertEquals(PENDING_RECEIVER.toString(), txn31.status)

    val contract = org.stellar.sdk.StrKey.encodeContract(ByteArray(32))
    val txn24 = stubTransaction("24", account)
    accept(ledgerOf(null, payment(to = muxed, from = contract)))
    assertEquals(PENDING_ANCHOR.toString(), txn24.status)
  }

  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun test_handle_accepts_muxed_destination_from_a_classic_sender(protocol: String) {
    val account = "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    val muxed = org.stellar.sdk.MuxedAccount(account, BigInteger.valueOf(12345)).address
    val txn = stubTransaction(protocol, account)

    accept(ledgerOf(null, payment(to = muxed)))

    assertEquals(
      if (protocol == "31") PENDING_RECEIVER.toString() else PENDING_ANCHOR.toString(),
      txn.status
    )

    // A muxed id that is not the stored memo is still refused.
    val untouched = stubTransaction(protocol, account)
    val otherId = org.stellar.sdk.MuxedAccount(account, BigInteger.valueOf(99)).address
    val ex = rejection(ledgerOf(null, payment(to = otherId)))
    assertContains(ex.message!!, "destination")
    assertNull(untouched.transferReceivedAt)
  }

  private fun rejectedLedger(cause: String): LedgerTransaction =
    when (cause) {
      "destination" -> ledgerOf(Memo.id(12345), payment(to = "someoneElse"))
      "memo" -> ledgerOf(Memo.id(54321), payment())
      "asset" -> ledgerOf(Memo.id(12345), payment(asset = eurcXdr))
      else -> ledgerOf(Memo.id(12345), payment(amount = 0))
    }

  @CsvSource(
    value =
      [
        "24,destination",
        "24,memo",
        "24,asset",
        "24,amount",
        "6,destination",
        "6,memo",
        "6,asset",
        "6,amount",
        "31,destination",
        "31,memo",
        "31,asset",
        "31,amount"
      ]
  )
  @ParameterizedTest
  fun test_handle_rejection_changes_nothing(protocol: String, cause: String) {
    val txn = stubTransaction(protocol)
    val before = gson.toJson(txn)

    rejection(rejectedLedger(cause))

    assertEquals(before, gson.toJson(txn))
    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
    verify(exactly = 0) { eventSession.publish(any()) }
  }

  /**
   * Hands the payment to the payment observer, with the stores answering the lookup it makes for
   * the transaction, and returns the hash the observer sent to the RPC.
   */
  private fun forwardedByObserver(
    protocol: String,
    txn: JdbcSepTransaction,
    account: String,
    sender: String,
    ledgerTxn: LedgerTransaction,
    destination: String,
  ): String {
    val sep31Store = mockk<JdbcSep31TransactionStore>(relaxed = true)
    val sep24Store = mockk<JdbcSep24TransactionStore>(relaxed = true)
    val sep6Store = mockk<JdbcSep6TransactionStore>(relaxed = true)
    val platformApiClient = mockk<PlatformApiClient>(relaxed = true)
    when (protocol) {
      "24" ->
        every {
          sep24Store.findAllByWithdrawAnchorAccountAndMemoAndStatus(
            account,
            LEDGER_MEMO,
            PENDING_USR_TRANSFER_START.toString()
          )
        } returns listOf(txn as JdbcSep24Transaction)
      "6" ->
        every {
          sep6Store.findAllByWithdrawAnchorAccountAndMemoAndStatus(
            account,
            LEDGER_MEMO,
            PENDING_USR_TRANSFER_START.toString()
          )
        } returns listOf(txn as JdbcSep6Transaction)
      else ->
        every {
          sep31Store.findAllByToAccountAndMemoAndStatus(
            account,
            LEDGER_MEMO,
            PENDING_SENDER.toString()
          )
        } returns listOf(txn as JdbcSep31Transaction)
    }
    val observer =
      DefaultPaymentListener(
        mockk(relaxed = true),
        sep31Store,
        sep24Store,
        sep6Store,
        platformApiClient,
        mockk(relaxed = true),
        assetService
      )
    observer.onReceived(
      PaymentTransferEvent.builder()
        .from(sender)
        .to(destination)
        .amount(BigInteger.valueOf(150000000))
        .sep11Asset(USDC_CODE_ISSUER)
        .txHash(STELLAR_TX_ID)
        .operationId("12345")
        .ledgerTransaction(ledgerTxn)
        .build()
    )
    val forwardedHash = slot<String>()
    verify(exactly = 1) {
      platformApiClient.notifyOnchainFundsReceived(TX_ID, capture(forwardedHash), any(), any())
    }
    return forwardedHash.captured
  }

  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun test_handle_accepts_the_payment_the_observer_forwards(protocol: String) {
    val account = "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    val sender = "GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5"
    val txn = stubTransaction(protocol, account)
    val ledgerTxn = ledgerOf(Memo.id(12345), payment(to = account, from = sender))

    val forwardedHash = forwardedByObserver(protocol, txn, account, sender, ledgerTxn, account)
    accept(ledgerTxn)

    assertEquals(STELLAR_TX_ID, forwardedHash)
    assertEquals(
      if (protocol == "31") PENDING_RECEIVER.toString() else PENDING_ANCHOR.toString(),
      txn.status
    )
  }

  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun test_handle_accepts_the_muxed_payment_the_observer_forwards(protocol: String) {
    val account = "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    val sender = "GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5"
    val muxed = org.stellar.sdk.MuxedAccount(account, BigInteger.valueOf(12345)).address
    val txn = stubTransaction(protocol, account)
    val ledgerTxn = ledgerOf(Memo.none(), payment(to = muxed, from = sender))

    val forwardedHash = forwardedByObserver(protocol, txn, account, sender, ledgerTxn, muxed)
    accept(ledgerTxn)

    assertEquals(STELLAR_TX_ID, forwardedHash)
    assertEquals(
      if (protocol == "31") PENDING_RECEIVER.toString() else PENDING_ANCHOR.toString(),
      txn.status
    )
  }

  /**
   * The observer looks a memo up by value only, so it forwards a text memo whose digits equal the
   * stored id memo; the RPC compares the memo by value too and accepts it (spec FGATE-13), so the
   * two never disagree on a payment.
   */
  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun test_handle_accepts_a_memo_of_another_type_the_observer_routes_by_value(protocol: String) {
    val account = "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    val sender = "GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5"
    val txn = stubTransaction(protocol, account)
    val ledgerTxn = ledgerOf(Memo.text("12345"), payment(to = account, from = sender))

    val forwardedHash = forwardedByObserver(protocol, txn, account, sender, ledgerTxn, account)
    accept(ledgerTxn)

    assertEquals(STELLAR_TX_ID, forwardedHash)
    assertEquals(
      if (protocol == "31") PENDING_RECEIVER.toString() else PENDING_ANCHOR.toString(),
      txn.status
    )
  }

  @CsvSource(value = ["24", "6"])
  @ParameterizedTest
  fun test_handle_on_hold_withdrawal_records_matching_payment(protocol: String) {
    val txn = stubTransaction(protocol, status = ON_HOLD.toString())
    assertNull(txn.transferReceivedAt)

    accept(ledgerOf(Memo.id(12345), payment()))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertEquals(Instant.parse(STELLAR_PAYMENT_DATE), txn.transferReceivedAt)
  }
}

private const val stellarTransactionRecord =
  """
[
  {
    "id": "stellarTxId",
    "created_at": "2023-05-10T10:18:20Z",
    "memo": "12345",
    "memo_type": "id",
    "envelope": "testEnvelopeXdr",
    "payments": [
      {
        "id": "12345",
        "amount": {
          "amount": "15.0000000",
          "asset": "USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
        },
        "asset_type": "credit_alphanum4",
        "payment_type": "payment",
        "source_account": "testFrom",
        "destination_account": "testTo"
      }
    ]
  }
]  
"""
