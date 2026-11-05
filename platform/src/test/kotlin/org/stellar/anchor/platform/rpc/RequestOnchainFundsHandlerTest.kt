package org.stellar.anchor.platform.rpc

import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import org.stellar.anchor.api.rpc.method.RequestOnchainFundsRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.*
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.data.JdbcSepTransaction
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager
import org.stellar.anchor.platform.service.*
import org.stellar.anchor.platform.utils.toRate
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24Transaction
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31Transaction
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6Transaction
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.MemoHelper
import org.stellar.anchor.util.SepRequestValidator

class RequestOnchainFundsHandlerTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val FIAT_USD = "iso4217:USD"
    private const val STELLAR_USDC =
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val STELLAR_USDC_CODE = "USDC"
    private const val STELLAR_USDC_ISSUER =
      "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val TEXT_MEMO = "testMemo"
    private const val TEXT_MEMO_TYPE = "text"
    private const val ID_MEMO_TYPE = "id"
    private const val DESTINATION_ACCOUNT = "testDestinationAccount"
    private const val DESTINATION_ACCOUNT_2 = "testDestinationAccount2"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"

    private val ID_MEMO = (10000..20000).random().toString()
  }

  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore

  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore

  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore

  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator

  @MockK(relaxed = true) private lateinit var assetService: AssetService

  @MockK(relaxed = true) private lateinit var sep6DepositInfoGenerator: Sep6DepositInfoNoneGenerator

  @MockK(relaxed = true)
  private lateinit var sep24DepositInfoGenerator: Sep24DepositInfoNoneGenerator

  @MockK(relaxed = true)
  private lateinit var sep31DepositInfoGenerator: Sep31DepositInfoNoneGenerator

  @MockK(relaxed = true)
  private lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager

  @MockK(relaxed = true) private lateinit var eventService: EventService

  @MockK(relaxed = true) private lateinit var metricsService: MetricsService

  @MockK(relaxed = true) private lateinit var eventSession: Session

  @MockK(relaxed = true) private lateinit var sepTransactionCounter: Counter

  @MockK(relaxed = true) private lateinit var sepRequestValidator: SepRequestValidator

  private lateinit var handler: RequestOnchainFundsHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    this.assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    this.handler =
      RequestOnchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        sep31DepositInfoGenerator,
        paymentObservingAccountsManager,
        eventService,
        metricsService,
        sepRequestValidator,
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
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
      "RPC method[request_onchain_funds] is not supported. Status[incomplete], kind[null], protocol[38], funds received[false]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidRequest() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
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
  fun test_handle_withoutAmounts_amountsAbsent() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
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
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.amountIn = "1"
    txn24.amountInAsset = STELLAR_USDC
    txn24.feeDetails = FeeDetails()
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
  fun test_handle__withoutAmounts_amount_fee_absent() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.amountIn = "1"
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountOut = "0.9"
    txn24.amountOutAsset = FIAT_USD
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
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", FIAT_USD))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .transactionId(TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(
      "All (amount_out is optional) or none of the amount_in, amount_out, and fee_details should be set",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidMemo() {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .transactionId(TX_ID)
        .memo(TEXT_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("Invalid id memo: Invalid memo testMemo of type: MEMO_ID", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_ok_missingMemo() {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .transactionId(TX_ID)
        .destinationAccount(DESTINATION_ACCOUNT)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("memo and memo_type are required", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_ok_missingDestinationAccount() {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .transactionId(TX_ID)
        .memo(ID_MEMO)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("destination_account is required", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidAmounts() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = STELLAR_USDC_CODE
    txn24.requestAssetIssuer = STELLAR_USDC_ISSUER
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
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = STELLAR_USDC_CODE
    txn24.requestAssetIssuer = STELLAR_USDC_ISSUER
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    request.amountIn.asset = FIAT_USD
    var ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals("amount_in.asset should be stellar asset", ex.message)
    request.amountIn.asset = STELLAR_USDC

    request.amountOut.asset = STELLAR_USDC
    ex = assertThrows { handler.handle(request) }
    assertEquals("amount_out.asset should be non-stellar asset", ex.message)
    request.amountOut.asset = FIAT_USD

    request.feeDetails.asset = FIAT_USD
    ex = assertThrows { handler.handle(request) }
    assertEquals("fee_details.asset should be stellar asset", ex.message)

    request.feeDetails = Amount("10", FIAT_USD).toRate()
    ex = assertThrows { handler.handle(request) }
    assertEquals("fee_details.asset should be stellar asset", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedStatus() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_TRUST.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[pending_trust], kind[withdrawal], protocol[24], funds received[false]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_transferReceived() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.transferReceivedAt = Instant.now()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[pending_anchor], kind[withdrawal], protocol[24], funds received[true]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedKind() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[incomplete], kind[deposit], protocol[24], funds received[false]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_with_quote_amount_out_missing() {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .feeDetails(Amount("0.1", STELLAR_USDC).toRate())
        .transactionId(TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
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
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .feeDetails(Amount("0.1", STELLAR_USDC).toRate())
        .transactionId(TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
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
  fun test_handle_sep24_arming_failure_does_not_save_or_publish() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("0.9", FIAT_USD))
        .feeDetails(Amount("0.1", STELLAR_USDC).toRate())
        .amountExpected(AmountRequest("1"))
        .memo(ID_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = STELLAR_USDC_CODE
    txn24.requestAssetIssuer = STELLAR_USDC_ISSUER

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every {
      paymentObservingAccountsManager.upsertOrThrow(
        any(),
        PaymentObservingAccountsManager.AccountType.TRANSIENT,
      )
    } throws RuntimeException("store unavailable")

    assertThrows<RuntimeException> { handler.handle(request) }

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { eventSession.publish(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_ok_sep24_withExpectedAmount() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("0.9", FIAT_USD))
        .feeDetails(Amount("0.1", STELLAR_USDC).toRate())
        .amountExpected(AmountRequest("1"))
        .memo(ID_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = STELLAR_USDC_CODE
    txn24.requestAssetIssuer = STELLAR_USDC_ISSUER
    txn24.userActionRequiredBy = Instant.now()
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep24") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }
    verify(exactly = 1) {
      paymentObservingAccountsManager.upsertOrThrow(
        DESTINATION_ACCOUNT,
        PaymentObservingAccountsManager.AccountType.TRANSIENT,
      )
    }
    verifyOrder {
      paymentObservingAccountsManager.upsertOrThrow(any(), any())
      txn24Store.save(any())
    }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = STELLAR_USDC_CODE
    expectedSep24Txn.requestAssetIssuer = STELLAR_USDC_ISSUER
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = STELLAR_USDC
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = FIAT_USD
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.memo = ID_MEMO
    expectedSep24Txn.memoType = ID_MEMO_TYPE
    expectedSep24Txn.toAccount = DESTINATION_ACCOUNT
    expectedSep24Txn.withdrawAnchorAccount = DESTINATION_ACCOUNT

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep24_ok_autogeneratedMemo() {
    val sep24DepositInfoGenerator: Sep24DepositInfoSelfGenerator = mockk()
    this.handler =
      RequestOnchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        sep31DepositInfoGenerator,
        paymentObservingAccountsManager,
        eventService,
        metricsService,
        sepRequestValidator,
      )

    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("0.9", FIAT_USD))
        .feeDetails(FeeDetails("0.1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = STELLAR_USDC_CODE
    txn24.requestAssetIssuer = STELLAR_USDC_ISSUER
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()
    val depositInfo = SepDepositInfo(DESTINATION_ACCOUNT_2, ID_MEMO)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { sep24DepositInfoGenerator.generate(ofType(Sep24Transaction::class)) } returns
      depositInfo
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep24") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = STELLAR_USDC_CODE
    expectedSep24Txn.requestAssetIssuer = STELLAR_USDC_ISSUER
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = STELLAR_USDC
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = FIAT_USD
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.memo = ID_MEMO
    expectedSep24Txn.memoType = ID_MEMO_TYPE
    expectedSep24Txn.toAccount = DESTINATION_ACCOUNT_2
    expectedSep24Txn.withdrawAnchorAccount = DESTINATION_ACCOUNT_2

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT_2
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep24_ok_withUserActionRequiredBy() {
    val actionRequiredBy = Instant.now().plusSeconds(100)
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("0.9", FIAT_USD))
        .feeDetails(FeeDetails("0.1", STELLAR_USDC))
        .memo(ID_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT)
        .userActionRequiredBy(actionRequiredBy)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = STELLAR_USDC_CODE
    txn24.requestAssetIssuer = STELLAR_USDC_ISSUER
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep24") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = STELLAR_USDC_CODE
    expectedSep24Txn.requestAssetIssuer = STELLAR_USDC_ISSUER
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = STELLAR_USDC
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = FIAT_USD
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.memo = ID_MEMO
    expectedSep24Txn.memoType = ID_MEMO_TYPE
    expectedSep24Txn.toAccount = DESTINATION_ACCOUNT
    expectedSep24Txn.withdrawAnchorAccount = DESTINATION_ACCOUNT
    expectedSep24Txn.userActionRequiredBy = actionRequiredBy

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT
    expectedResponse.userActionRequiredBy = actionRequiredBy
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep24_ok_withoutAmounts_amountsPresent() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .memo(ID_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = STELLAR_USDC_CODE
    txn24.requestAssetIssuer = STELLAR_USDC_ISSUER
    txn24.amountIn = "1"
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountOut = "0.9"
    txn24.amountOutAsset = FIAT_USD
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
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep24") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep24Txn = JdbcSep24Transaction()
    expectedSep24Txn.kind = WITHDRAWAL.kind
    expectedSep24Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.requestAssetCode = STELLAR_USDC_CODE
    expectedSep24Txn.requestAssetIssuer = STELLAR_USDC_ISSUER
    expectedSep24Txn.amountIn = "1"
    expectedSep24Txn.amountInAsset = STELLAR_USDC
    expectedSep24Txn.amountOut = "0.9"
    expectedSep24Txn.amountOutAsset = FIAT_USD
    expectedSep24Txn.amountFee = "0.1"
    expectedSep24Txn.amountFeeAsset = STELLAR_USDC
    expectedSep24Txn.amountExpected = "1"
    expectedSep24Txn.memo = ID_MEMO
    expectedSep24Txn.memoType = ID_MEMO_TYPE
    expectedSep24Txn.toAccount = DESTINATION_ACCOUNT
    expectedSep24Txn.withdrawAnchorAccount = DESTINATION_ACCOUNT

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = WITHDRAWAL
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep24TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep24_notNoneGenerator() {
    val sep24DepositInfoGenerator: Sep24DepositInfoSelfGenerator = mockk()
    this.handler =
      RequestOnchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        sep31DepositInfoGenerator,
        paymentObservingAccountsManager,
        eventService,
        metricsService,
        sepRequestValidator,
      )

    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .memo(TEXT_MEMO)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(
      """Anchor is not configured to accept memo, memo_type and destination_account. Please set configuration deposit_info_generator_type to 'none' if you want to enable this feature""",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_unsupportedStatus(kind: String) {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_TRUST.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[pending_trust], kind[$kind], protocol[6], funds received[false]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_transferReceived(kind: String) {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = kind
    txn6.transferReceivedAt = Instant.now()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[pending_anchor], kind[$kind], protocol[6], funds received[true]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_transferReceived_pendingCustomerInfoUpdate_rejected(kind: String) {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val receivedAt = Instant.now()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_CUSTOMER_INFO_UPDATE.toString()
    txn6.kind = kind
    txn6.transferReceivedAt = receivedAt
    txn6.memo = TEXT_MEMO
    txn6.memoType = TEXT_MEMO_TYPE
    txn6.amountIn = "1"
    txn6.amountExpected = "1"

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep6") } returns
      sepTransactionCounter

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[pending_customer_info_update], kind[$kind], protocol[6], funds received[true]",
      ex.message,
    )

    assertEquals(PENDING_CUSTOMER_INFO_UPDATE.toString(), txn6.status)
    assertEquals(TEXT_MEMO, txn6.memo)
    assertEquals(TEXT_MEMO_TYPE, txn6.memoType)
    assertEquals("1", txn6.amountIn)
    assertEquals("1", txn6.amountExpected)
    assertEquals(receivedAt, txn6.transferReceivedAt)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(
    value =
      [
        "withdrawal,pending_customer_info_update",
        "withdrawal-exchange,pending_customer_info_update",
        "withdrawal,pending_anchor",
        "withdrawal-exchange,pending_anchor",
        "withdrawal,incomplete",
        "withdrawal-exchange,incomplete",
      ]
  )
  @ParameterizedTest
  fun test_handle_sep6_notReceived_supportedStatuses_accepted(kind: String, status: String) {
    val sep6DepositInfoGenerator: Sep6DepositInfoSelfGenerator = mockk()
    this.handler =
      RequestOnchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        sep31DepositInfoGenerator,
        paymentObservingAccountsManager,
        eventService,
        metricsService,
        sepRequestValidator,
      )

    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("0.9", FIAT_USD))
        .feeDetails(FeeDetails("0.1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = status
    txn6.kind = kind
    txn6.requestAssetCode = STELLAR_USDC_CODE
    txn6.requestAssetIssuer = STELLAR_USDC_ISSUER
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val depositInfo = SepDepositInfo(DESTINATION_ACCOUNT_2, ID_MEMO)

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { sep6DepositInfoGenerator.generate(ofType(Sep6Transaction::class)) } returns depositInfo
    every { eventSession.publish(any()) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep6") } returns
      sepTransactionCounter

    handler.handle(request)

    verify(exactly = 1) { txn6Store.save(any()) }
    assertEquals(PENDING_USR_TRANSFER_START.toString(), sep6TxnCapture.captured.status)
    assertEquals(ID_MEMO, sep6TxnCapture.captured.memo)
  }

  @CsvSource(
    value =
      [
        "deposit,pending_customer_info_update",
        "deposit,pending_anchor",
        "deposit-exchange,pending_customer_info_update",
        "deposit-exchange,pending_anchor",
      ]
  )
  @ParameterizedTest
  fun test_handle_sep6_deposit_pendingStatuses_rejected(kind: String, status: String) {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = status
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[$status], kind[$kind], protocol[6], funds received[false]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
  }

  @Test
  fun test_handle_sep24_pendingCustomerInfoUpdate_rejected() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_CUSTOMER_INFO_UPDATE.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[pending_customer_info_update], kind[withdrawal], protocol[24], funds received[false]",
      ex.message,
    )

    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_unsupportedKind(kind: String) {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[incomplete], kind[$kind], protocol[6], funds received[false]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @ParameterizedTest
  @ValueSource(strings = ["withdrawal", "withdrawal-exchange"])
  fun test_handle_sep6_with_quote_amount_out_missing(kind: String) {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .feeDetails(Amount("0.1", STELLAR_USDC).toRate())
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
  @ValueSource(strings = ["withdrawal", "withdrawal-exchange"])
  fun test_handle_sep6_with_simple_quote(kind: String) {
    val request =
      RequestOnchainFundsRequest.builder()
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .feeDetails(Amount("0.1", STELLAR_USDC).toRate())
        .transactionId(TX_ID)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind
    txn6.amountInAsset = STELLAR_USDC
    txn6.amountOutAsset = STELLAR_USDC
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

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_ok_autogeneratedMemo(kind: String) {
    val sep6DepositInfoGenerator: Sep6DepositInfoSelfGenerator = mockk()
    this.handler =
      RequestOnchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        sep31DepositInfoGenerator,
        paymentObservingAccountsManager,
        eventService,
        metricsService,
        sepRequestValidator,
      )

    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("0.9", FIAT_USD))
        .feeDetails(FeeDetails("0.1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind
    txn6.requestAssetCode = STELLAR_USDC_CODE
    txn6.requestAssetIssuer = STELLAR_USDC_ISSUER
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()
    val depositInfo = SepDepositInfo(DESTINATION_ACCOUNT_2, ID_MEMO)

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { sep6DepositInfoGenerator.generate(ofType(Sep6Transaction::class)) } returns depositInfo
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep6") } returns
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
    expectedSep6Txn.requestAssetCode = STELLAR_USDC_CODE
    expectedSep6Txn.requestAssetIssuer = STELLAR_USDC_ISSUER
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = STELLAR_USDC
    expectedSep6Txn.amountOut = "0.9"
    expectedSep6Txn.amountOutAsset = FIAT_USD
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = STELLAR_USDC
    expectedSep6Txn.amountExpected = "1"
    expectedSep6Txn.memo = ID_MEMO
    expectedSep6Txn.memoType = ID_MEMO_TYPE
    expectedSep6Txn.withdrawAnchorAccount = DESTINATION_ACCOUNT_2
    expectedSep6Txn.toAccount = DESTINATION_ACCOUNT_2

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT_2
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep6TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep6TxnCapture.captured.updatedAt <= endDate)
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_ok_withoutAmountExpected(kind: String) {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("0.9", FIAT_USD))
        .feeDetails(FeeDetails("0.1", STELLAR_USDC))
        .memo(ID_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind
    txn6.requestAssetCode = STELLAR_USDC_CODE
    txn6.requestAssetIssuer = STELLAR_USDC_ISSUER
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

    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }
    verify(exactly = 1) {
      paymentObservingAccountsManager.upsertOrThrow(
        DESTINATION_ACCOUNT,
        PaymentObservingAccountsManager.AccountType.TRANSIENT,
      )
    }
    verifyOrder {
      paymentObservingAccountsManager.upsertOrThrow(any(), any())
      txn6Store.save(any())
    }

    val expectedSep6Txn = JdbcSep6Transaction()
    expectedSep6Txn.kind = kind
    expectedSep6Txn.status = PENDING_USR_TRANSFER_START.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.requestAssetCode = STELLAR_USDC_CODE
    expectedSep6Txn.requestAssetIssuer = STELLAR_USDC_ISSUER
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = STELLAR_USDC
    expectedSep6Txn.amountOut = "0.9"
    expectedSep6Txn.amountOutAsset = FIAT_USD
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = STELLAR_USDC
    expectedSep6Txn.amountExpected = "1"
    expectedSep6Txn.memo = ID_MEMO
    expectedSep6Txn.memoType = ID_MEMO_TYPE
    expectedSep6Txn.withdrawAnchorAccount = DESTINATION_ACCOUNT
    expectedSep6Txn.toAccount = DESTINATION_ACCOUNT

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep6TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep6TxnCapture.captured.updatedAt <= endDate)
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_ok_withoutAmounts_amountsPresent(kind: String) {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .memo(ID_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind
    txn6.requestAssetCode = STELLAR_USDC_CODE
    txn6.requestAssetIssuer = STELLAR_USDC_ISSUER
    txn6.amountIn = "1"
    txn6.amountInAsset = STELLAR_USDC
    txn6.amountOut = "0.9"
    txn6.amountOutAsset = FIAT_USD
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
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep6") } returns
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
    expectedSep6Txn.requestAssetCode = STELLAR_USDC_CODE
    expectedSep6Txn.requestAssetIssuer = STELLAR_USDC_ISSUER
    expectedSep6Txn.amountIn = "1"
    expectedSep6Txn.amountInAsset = STELLAR_USDC
    expectedSep6Txn.amountOut = "0.9"
    expectedSep6Txn.amountOutAsset = FIAT_USD
    expectedSep6Txn.amountFee = "0.1"
    expectedSep6Txn.amountFeeAsset = STELLAR_USDC
    expectedSep6Txn.amountExpected = "1"
    expectedSep6Txn.memo = ID_MEMO
    expectedSep6Txn.memoType = ID_MEMO_TYPE
    expectedSep6Txn.withdrawAnchorAccount = DESTINATION_ACCOUNT
    expectedSep6Txn.toAccount = DESTINATION_ACCOUNT

    JSONAssert.assertEquals(
      gson.toJson(expectedSep6Txn),
      gson.toJson(sep6TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_6
    expectedResponse.kind = PlatformTransactionData.Kind.from(kind)
    expectedResponse.status = PENDING_USR_TRANSFER_START
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))
    expectedResponse.creator = StellarId(null, null, null)

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep6TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep6TxnCapture.captured.updatedAt <= endDate)
  }

  @CsvSource(value = ["withdrawal", "withdrawal-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_notNoneGenerator(kind: String) {
    val sep6DepositInfoGenerator: Sep6DepositInfoSelfGenerator = mockk()
    this.handler =
      RequestOnchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        sep31DepositInfoGenerator,
        paymentObservingAccountsManager,
        eventService,
        metricsService,
        sepRequestValidator,
      )

    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .memo(TEXT_MEMO)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind
    val sep6TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep6TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(
      """
  Anchor is not configured to accept memo, memo_type and destination_account. Please set configuration deposit_info_generator_type to 'none' if you want to enable this feature
  """
        .trimIndent(),
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_unsupportedStatus() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_TRUST.toString()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns txn31

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[pending_trust], kind[receive], protocol[31], funds received[false]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_transferReceived() {
    val request = RequestOnchainFundsRequest.builder().transactionId(TX_ID).build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    txn31.transferReceivedAt = Instant.now()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[request_onchain_funds] is not supported. Status[pending_receiver], kind[receive], protocol[31], funds received[true]",
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep31_ok_autogeneratedMemo() {
    val sep31DepositInfoGenerator: Sep31DepositInfoSelfGenerator = mockk()
    this.handler =
      RequestOnchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        sep31DepositInfoGenerator,
        paymentObservingAccountsManager,
        eventService,
        metricsService,
        sepRequestValidator,
      )

    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("0.9", FIAT_USD))
        .feeDetails(FeeDetails("0.1", STELLAR_USDC))
        .amountExpected(AmountRequest("1"))
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    val sep31TxnCapture = slot<JdbcSep31Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()
    val depositInfo = SepDepositInfo(DESTINATION_ACCOUNT_2, ID_MEMO)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { sep31DepositInfoGenerator.generate(ofType(Sep31Transaction::class)) } returns
      depositInfo
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep31Txn = JdbcSep31Transaction()
    expectedSep31Txn.status = PENDING_SENDER.toString()
    expectedSep31Txn.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedSep31Txn.amountIn = "1"
    expectedSep31Txn.amountInAsset = STELLAR_USDC
    expectedSep31Txn.amountOut = "0.9"
    expectedSep31Txn.amountOutAsset = FIAT_USD
    expectedSep31Txn.amountFee = "0.1"
    expectedSep31Txn.amountFeeAsset = STELLAR_USDC
    expectedSep31Txn.amountExpected = "1"
    expectedSep31Txn.stellarMemo = ID_MEMO
    expectedSep31Txn.stellarMemoType = ID_MEMO_TYPE
    expectedSep31Txn.toAccount = DESTINATION_ACCOUNT_2

    JSONAssert.assertEquals(
      gson.toJson(expectedSep31Txn),
      gson.toJson(sep31TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_31
    expectedResponse.status = PENDING_SENDER
    expectedResponse.kind = PlatformTransactionData.Kind.RECEIVE
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT_2
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.refundMemo = ID_MEMO
    expectedResponse.refundMemoType = ID_MEMO_TYPE
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep31TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep31TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep31_ok_withoutAmountExpected() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("0.9", FIAT_USD))
        .feeDetails(FeeDetails("0.1", STELLAR_USDC))
        .memo(ID_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT)
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    val sep31TxnCapture = slot<JdbcSep31Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }
    verify(exactly = 1) {
      paymentObservingAccountsManager.upsertOrThrow(
        DESTINATION_ACCOUNT,
        PaymentObservingAccountsManager.AccountType.TRANSIENT,
      )
    }
    verifyOrder {
      paymentObservingAccountsManager.upsertOrThrow(any(), any())
      txn31Store.save(any())
    }

    val expectedSep31Txn = JdbcSep31Transaction()
    expectedSep31Txn.status = PENDING_SENDER.toString()
    expectedSep31Txn.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedSep31Txn.amountIn = "1"
    expectedSep31Txn.amountInAsset = STELLAR_USDC
    expectedSep31Txn.amountOut = "0.9"
    expectedSep31Txn.amountOutAsset = FIAT_USD
    expectedSep31Txn.amountFee = "0.1"
    expectedSep31Txn.amountFeeAsset = STELLAR_USDC
    expectedSep31Txn.amountExpected = "1"
    expectedSep31Txn.stellarMemo = ID_MEMO
    expectedSep31Txn.stellarMemoType = ID_MEMO_TYPE
    expectedSep31Txn.toAccount = DESTINATION_ACCOUNT

    JSONAssert.assertEquals(
      gson.toJson(expectedSep31Txn),
      gson.toJson(sep31TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_31
    expectedResponse.kind = PlatformTransactionData.Kind.RECEIVE
    expectedResponse.status = PENDING_SENDER
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.refundMemo = ID_MEMO
    expectedResponse.refundMemoType = ID_MEMO_TYPE
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep31TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep31TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep31_ok_withoutAmounts_amountsPresent() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .memo(ID_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT)
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    txn31.amountIn = "1"
    txn31.amountInAsset = STELLAR_USDC
    txn31.amountOut = "0.9"
    txn31.amountOutAsset = FIAT_USD
    txn31.amountFee = "0.1"
    txn31.amountFeeAsset = STELLAR_USDC
    txn31.amountExpected = "1"
    val sep31TxnCapture = slot<JdbcSep31Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null
    every { eventSession.publish(capture(anchorEventCapture)) } just Runs
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    val startDate = Instant.now()
    val response = handler.handle(request)
    val endDate = Instant.now()

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 1) { sepTransactionCounter.increment() }

    val expectedSep31Txn = JdbcSep31Transaction()
    expectedSep31Txn.status = PENDING_SENDER.toString()
    expectedSep31Txn.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedSep31Txn.amountIn = "1"
    expectedSep31Txn.amountInAsset = STELLAR_USDC
    expectedSep31Txn.amountOut = "0.9"
    expectedSep31Txn.amountOutAsset = FIAT_USD
    expectedSep31Txn.amountFee = "0.1"
    expectedSep31Txn.amountFeeAsset = STELLAR_USDC
    expectedSep31Txn.amountExpected = "1"
    expectedSep31Txn.toAccount = DESTINATION_ACCOUNT
    expectedSep31Txn.stellarMemo = ID_MEMO
    expectedSep31Txn.stellarMemoType = ID_MEMO_TYPE

    JSONAssert.assertEquals(
      gson.toJson(expectedSep31Txn),
      gson.toJson(sep31TxnCapture.captured),
      JSONCompareMode.STRICT,
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_31
    expectedResponse.kind = PlatformTransactionData.Kind.RECEIVE
    expectedResponse.status = PENDING_SENDER
    expectedResponse.amountIn = Amount("1", STELLAR_USDC)
    expectedResponse.amountOut = Amount("0.9", FIAT_USD)
    expectedResponse.feeDetails = Amount("0.1", STELLAR_USDC).toRate()
    expectedResponse.amountExpected = Amount("1", STELLAR_USDC)
    expectedResponse.updatedAt = sep31TxnCapture.captured.updatedAt
    expectedResponse.destinationAccount = DESTINATION_ACCOUNT
    expectedResponse.memo = ID_MEMO
    expectedResponse.memoType = ID_MEMO_TYPE
    expectedResponse.refundMemo = ID_MEMO
    expectedResponse.refundMemoType = ID_MEMO_TYPE
    expectedResponse.customers = Customers(StellarId(null, null, null), StellarId(null, null, null))

    JSONAssert.assertEquals(
      gson.toJson(expectedResponse),
      gson.toJson(response),
      JSONCompareMode.STRICT,
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
      JSONCompareMode.STRICT,
    )

    assertTrue(sep31TxnCapture.captured.updatedAt >= startDate)
    assertTrue(sep31TxnCapture.captured.updatedAt <= endDate)
  }

  @Test
  fun test_handle_sep31_notNoneGenerator() {
    val sep31DepositInfoGenerator: Sep31DepositInfoSelfGenerator = mockk()
    this.handler =
      RequestOnchainFundsHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        sep6DepositInfoGenerator,
        sep24DepositInfoGenerator,
        sep31DepositInfoGenerator,
        paymentObservingAccountsManager,
        eventService,
        metricsService,
        sepRequestValidator,
      )

    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .memo(ID_MEMO)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .build()
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    val sep31TxnCapture = slot<JdbcSep31Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(sep31TxnCapture)) } returns null

    val ex = assertThrows<InvalidParamsException> { handler.handle(request) }
    assertEquals(
      """
  Anchor is not configured to accept memo, memo_type and destination_account. Please set configuration deposit_info_generator_type to 'none' if you want to enable this feature
  """
        .trimIndent(),
      ex.message,
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_memo_in_use_returns_400() {
    val request =
      RequestOnchainFundsRequest.builder()
        .transactionId(TX_ID)
        .memo(ID_MEMO)
        .destinationAccount(DESTINATION_ACCOUNT_2)
        .amountIn(AmountAssetRequest("1", STELLAR_USDC))
        .amountOut(AmountAssetRequest("1", FIAT_USD))
        .feeDetails(FeeDetails("1", STELLAR_USDC))
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.amountInAsset = STELLAR_USDC
    txn24.amountOutAsset = FIAT_USD

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(any()) } throws
      org.springframework.dao.DataIntegrityViolationException("duplicate key")

    val ex = assertThrows<BadRequestException> { handler.handle(request) }
    assertTrue(ex.message!!.contains("memo_in_use"))
  }

  private fun noneGeneratorRequest(memo: String) =
    RequestOnchainFundsRequest.builder()
      .transactionId(TX_ID)
      .amountIn(AmountAssetRequest("1", STELLAR_USDC))
      .amountOut(AmountAssetRequest("0.9", FIAT_USD))
      .feeDetails(FeeDetails("0.1", STELLAR_USDC))
      .memo(memo)
      .destinationAccount(DESTINATION_ACCOUNT)
      .build()

  @CsvSource(
    "007,7",
    "+7,7",
    "0,0",
    "12345,12345",
    "18446744073709551615,18446744073709551615",
  )
  @ParameterizedTest
  fun test_handle_sep6_noneGenerator_storesCanonicalIdMemo(input: String, expected: String) {
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = WITHDRAWAL.kind
    txn6.requestAssetCode = STELLAR_USDC_CODE
    txn6.requestAssetIssuer = STELLAR_USDC_ISSUER
    val captured = slot<JdbcSep6Transaction>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(captured)) } returns null
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep6") } returns
      sepTransactionCounter

    handler.handle(noneGeneratorRequest(input))

    assertEquals(expected, captured.captured.memo)
    assertEquals(ID_MEMO_TYPE, captured.captured.memoType)
    assertEquals(
      MemoHelper.memoAsString(MemoHelper.makeMemo(captured.captured.memo, ID_MEMO_TYPE)),
      captured.captured.memo,
    )
  }

  @CsvSource(
    "007,7",
    "+7,7",
    "0,0",
    "12345,12345",
    "18446744073709551615,18446744073709551615",
  )
  @ParameterizedTest
  fun test_handle_sep24_noneGenerator_storesCanonicalIdMemo(input: String, expected: String) {
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = WITHDRAWAL.kind
    txn24.requestAssetCode = STELLAR_USDC_CODE
    txn24.requestAssetIssuer = STELLAR_USDC_ISSUER
    val captured = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(captured)) } returns null
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep24") } returns
      sepTransactionCounter

    handler.handle(noneGeneratorRequest(input))

    assertEquals(expected, captured.captured.memo)
    assertEquals(ID_MEMO_TYPE, captured.captured.memoType)
    assertEquals(
      MemoHelper.memoAsString(MemoHelper.makeMemo(captured.captured.memo, ID_MEMO_TYPE)),
      captured.captured.memo,
    )
  }

  @CsvSource(
    "007,7",
    "+7,7",
    "0,0",
    "12345,12345",
    "18446744073709551615,18446744073709551615",
  )
  @ParameterizedTest
  fun test_handle_sep31_noneGenerator_storesCanonicalIdMemo(input: String, expected: String) {
    val txn31 = JdbcSep31Transaction()
    txn31.status = PENDING_RECEIVER.toString()
    val captured = slot<JdbcSep31Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(TX_ID) } returns txn31
    every { txn31Store.save(capture(captured)) } returns null
    every { metricsService.counter(AnchorMetrics.PLATFORM_RPC_TRANSACTION, "SEP", "sep31") } returns
      sepTransactionCounter

    handler.handle(noneGeneratorRequest(input))

    assertEquals(expected, captured.captured.stellarMemo)
    assertEquals(ID_MEMO_TYPE, captured.captured.stellarMemoType)
    assertEquals(
      MemoHelper.memoAsString(MemoHelper.makeMemo(captured.captured.stellarMemo, ID_MEMO_TYPE)),
      captured.captured.stellarMemo,
    )
  }

  // --- A memo is never assigned twice across SEP-6, SEP-24 and SEP-31 (ANCHOR-1323) ---

  private val memoInUseMessage =
    "memo_in_use: the requested memo and destination account are already assigned to a pending transaction"

  private fun pendingStatusOf(protocol: String) =
    if (protocol == "31") PENDING_SENDER.toString() else PENDING_USR_TRANSFER_START.toString()

  private fun selfGeneratorHandler(): RequestOnchainFundsHandler {
    val depositInfo = SepDepositInfo(DESTINATION_ACCOUNT_2, ID_MEMO)
    val generator6: Sep6DepositInfoSelfGenerator = mockk()
    val generator24: Sep24DepositInfoSelfGenerator = mockk()
    val generator31: Sep31DepositInfoSelfGenerator = mockk()
    every { generator6.generate(ofType(Sep6Transaction::class)) } returns depositInfo
    every { generator24.generate(ofType(Sep24Transaction::class)) } returns depositInfo
    every { generator31.generate(ofType(Sep31Transaction::class)) } returns depositInfo
    return RequestOnchainFundsHandler(
      txn6Store,
      txn24Store,
      txn31Store,
      requestValidator,
      assetService,
      generator6,
      generator24,
      generator31,
      paymentObservingAccountsManager,
      eventService,
      metricsService,
      sepRequestValidator,
    )
  }

  private fun selfGeneratorRequest(transactionId: String = TX_ID) =
    RequestOnchainFundsRequest.builder()
      .transactionId(transactionId)
      .amountIn(AmountAssetRequest("1", STELLAR_USDC))
      .amountOut(AmountAssetRequest("0.9", FIAT_USD))
      .feeDetails(FeeDetails("0.1", STELLAR_USDC))
      .amountExpected(AmountRequest("1"))
      .build()

  private fun businessServerMemoRequest(memo: String) =
    RequestOnchainFundsRequest.builder()
      .transactionId(TX_ID)
      .amountIn(AmountAssetRequest("1", STELLAR_USDC))
      .amountOut(AmountAssetRequest("0.9", FIAT_USD))
      .feeDetails(FeeDetails("0.1", STELLAR_USDC))
      .memo(memo)
      .destinationAccount(DESTINATION_ACCOUNT_2)
      .build()

  /** A withdrawal (a receive for SEP-31) that has no memo yet and waits for its deposit info. */
  private fun newWithdrawal(protocol: String, id: String = TX_ID): JdbcSepTransaction =
    when (protocol) {
      "6" ->
        JdbcSep6Transaction().also {
          it.id = id
          it.status = INCOMPLETE.toString()
          it.kind = "withdrawal"
          it.requestAssetCode = STELLAR_USDC_CODE
          it.requestAssetIssuer = STELLAR_USDC_ISSUER
        }
      "24" ->
        JdbcSep24Transaction().also {
          it.id = id
          it.status = INCOMPLETE.toString()
          it.kind = WITHDRAWAL.kind
          it.requestAssetCode = STELLAR_USDC_CODE
          it.requestAssetIssuer = STELLAR_USDC_ISSUER
        }
      else ->
        JdbcSep31Transaction().also {
          it.id = id
          it.status = PENDING_RECEIVER.toString()
        }
    }

  /** A transaction that already waits for a payment to DESTINATION_ACCOUNT_2 with ID_MEMO. */
  private fun pendingRow(protocol: String, id: String): JdbcSepTransaction =
    when (protocol) {
      "6" ->
        JdbcSep6Transaction().also {
          it.id = id
          it.status = PENDING_USR_TRANSFER_START.toString()
          it.kind = "withdrawal"
          it.withdrawAnchorAccount = DESTINATION_ACCOUNT_2
          it.memo = ID_MEMO
          it.memoType = ID_MEMO_TYPE
        }
      "24" ->
        JdbcSep24Transaction().also {
          it.id = id
          it.status = PENDING_USR_TRANSFER_START.toString()
          it.kind = WITHDRAWAL.kind
          it.withdrawAnchorAccount = DESTINATION_ACCOUNT_2
          it.memo = ID_MEMO
          it.memoType = ID_MEMO_TYPE
        }
      else ->
        JdbcSep31Transaction().also {
          it.id = id
          it.status = PENDING_SENDER.toString()
          it.toAccount = DESTINATION_ACCOUNT_2
          it.stellarMemo = ID_MEMO
          it.stellarMemoType = ID_MEMO_TYPE
        }
    }

  private fun serveTransaction(protocol: String, txn: JdbcSepTransaction) {
    every { txn6Store.findByTransactionId(any()) } returns
      (if (protocol == "6") txn as JdbcSep6Transaction else null)
    every { txn24Store.findByTransactionId(any()) } returns
      (if (protocol == "24") txn as JdbcSep24Transaction else null)
    every { txn31Store.findByTransactionId(any()) } returns
      (if (protocol == "31") txn as JdbcSep31Transaction else null)
  }

  /**
   * Makes the memo lookups of each protocol return its row, but only when the lookup asks for the
   * status in [servedStatus] (the pending status of the protocol by default) and for
   * DESTINATION_ACCOUNT_2 with ID_MEMO.
   */
  private fun stubPendingRows(
    rows: Map<String, JdbcSepTransaction>,
    servedStatus: (String) -> String = { pendingStatusOf(it) },
  ) {
    fun matches(protocol: String, account: String, memo: String, status: String) =
      rows.containsKey(protocol) &&
        account == DESTINATION_ACCOUNT_2 &&
        memo == ID_MEMO &&
        status == servedStatus(protocol)
    every { txn6Store.findAllByWithdrawAnchorAccountAndMemoAndStatus(any(), any(), any()) } answers
      {
        if (matches("6", arg(0), arg(1), arg(2))) listOf(rows["6"] as JdbcSep6Transaction)
        else emptyList()
      }
    every { txn24Store.findAllByWithdrawAnchorAccountAndMemoAndStatus(any(), any(), any()) } answers
      {
        if (matches("24", arg(0), arg(1), arg(2))) listOf(rows["24"] as JdbcSep24Transaction)
        else emptyList()
      }
    every { txn31Store.findAllByToAccountAndMemoAndStatus(any(), any(), any()) } answers
      {
        if (matches("31", arg(0), arg(1), arg(2))) listOf(rows["31"] as JdbcSep31Transaction)
        else emptyList()
      }
  }

  private fun resetRecordedCalls() {
    clearMocks(
      txn6Store,
      txn24Store,
      txn31Store,
      eventSession,
      paymentObservingAccountsManager,
      answers = false,
    )
  }

  private fun memoOf(txn: JdbcSepTransaction): String? =
    when (txn) {
      is JdbcSep6Transaction -> txn.memo
      is JdbcSep24Transaction -> txn.memo
      is JdbcSep31Transaction -> txn.stellarMemo
      else -> null
    }

  private fun accountOf(txn: JdbcSepTransaction): String? =
    when (txn) {
      is JdbcSep6Transaction -> txn.withdrawAnchorAccount
      is JdbcSep24Transaction -> txn.withdrawAnchorAccount
      is JdbcSep31Transaction -> txn.toAccount
      else -> null
    }

  private fun assertNothingSaved() {
    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
  }

  private fun assertRefused(target: String, pendingProtocol: String) {
    resetRecordedCalls()
    val txn = newWithdrawal(target)
    serveTransaction(target, txn)
    stubPendingRows(mapOf(pendingProtocol to pendingRow(pendingProtocol, "other-row")))

    val ex =
      assertThrows<BadRequestException> { selfGeneratorHandler().handle(selfGeneratorRequest()) }

    assertEquals(
      memoInUseMessage,
      ex.message,
      "SEP-$target assignment vs pending SEP-$pendingProtocol"
    )
    assertNothingSaved()
  }

  @Test
  fun test_handle_refuses_a_memo_pending_on_a_sep24_row() {
    assertRefused("24", "24")
    assertRefused("6", "24")
  }

  @Test
  fun test_handle_refuses_a_memo_pending_in_another_protocol() {
    assertRefused("24", "6")
    assertRefused("6", "24")
    assertRefused("24", "31")
    assertRefused("6", "31")
    assertRefused("31", "6")
    assertRefused("31", "24")
  }

  @Test
  fun test_handle_accepts_a_memo_held_only_by_the_updated_row() {
    for (target in listOf("24", "6", "31")) {
      resetRecordedCalls()
      val txn = newWithdrawal(target)
      serveTransaction(target, txn)
      stubPendingRows(mapOf(target to pendingRow(target, TX_ID)))

      selfGeneratorHandler().handle(selfGeneratorRequest())

      assertEquals(ID_MEMO, memoOf(txn), "SEP-$target memo")
      assertEquals(DESTINATION_ACCOUNT_2, accountOf(txn), "SEP-$target account")
      when (target) {
        "6" -> verify(exactly = 1) { txn6Store.save(any()) }
        "24" -> verify(exactly = 1) { txn24Store.save(any()) }
        else -> verify(exactly = 1) { txn31Store.save(any()) }
      }
    }
  }

  @Test
  fun test_handle_accepts_a_memo_held_by_rows_in_other_statuses() {
    for (target in listOf("24", "6", "31")) {
      resetRecordedCalls()
      val txn = newWithdrawal(target)
      serveTransaction(target, txn)
      // Every protocol holds a row for the memo, but only under a status that is not pending.
      stubPendingRows(
        mapOf(
          "6" to pendingRow("6", "row-6"),
          "24" to pendingRow("24", "row-24"),
          "31" to pendingRow("31", "row-31"),
        )
      ) {
        "expired"
      }

      selfGeneratorHandler().handle(selfGeneratorRequest())

      assertEquals(ID_MEMO, memoOf(txn), "SEP-$target memo")
      verify {
        txn24Store.findAllByWithdrawAnchorAccountAndMemoAndStatus(
          DESTINATION_ACCOUNT_2,
          ID_MEMO,
          "pending_user_transfer_start",
        )
        txn6Store.findAllByWithdrawAnchorAccountAndMemoAndStatus(
          DESTINATION_ACCOUNT_2,
          ID_MEMO,
          "pending_user_transfer_start",
        )
        txn31Store.findAllByToAccountAndMemoAndStatus(
          DESTINATION_ACCOUNT_2,
          ID_MEMO,
          "pending_sender",
        )
      }
    }
  }

  @Test
  fun test_handle_refusal_leaves_the_transaction_unsaved() {
    for ((target, pendingProtocol) in listOf("24" to "6", "31" to "24", "6" to "31")) {
      resetRecordedCalls()
      val txn = newWithdrawal(target)
      val statusBefore = txn.status
      serveTransaction(target, txn)
      stubPendingRows(mapOf(pendingProtocol to pendingRow(pendingProtocol, "other-row")))

      assertThrows<BadRequestException> { selfGeneratorHandler().handle(selfGeneratorRequest()) }

      assertEquals(statusBefore, txn.status, "SEP-$target status")
      assertNull(memoOf(txn), "SEP-$target memo")
      assertNull(accountOf(txn), "SEP-$target account")
      assertNothingSaved()
      verify(exactly = 0) { eventSession.publish(any()) }
      verify(exactly = 0) { paymentObservingAccountsManager.upsertOrThrow(any(), any()) }
    }
  }

  @Test
  fun test_handle_refuses_a_business_server_memo_pending_elsewhere() {
    // The `none` generator handler of setup(): the business server supplies memo and account.
    // A memo written with a leading zero is the same memo, so the check must use the canonical one.
    for ((target, pendingProtocol, memo) in
      listOf(
        Triple("24", "6", ID_MEMO),
        Triple("24", "6", "0$ID_MEMO"),
        Triple("6", "31", ID_MEMO),
        Triple("6", "31", "0$ID_MEMO"),
        Triple("31", "24", ID_MEMO),
        Triple("31", "24", "0$ID_MEMO"),
      )) {
      resetRecordedCalls()
      val txn = newWithdrawal(target)
      serveTransaction(target, txn)
      stubPendingRows(mapOf(pendingProtocol to pendingRow(pendingProtocol, "other-row")))

      val ex = assertThrows<BadRequestException> { handler.handle(businessServerMemoRequest(memo)) }

      assertEquals(memoInUseMessage, ex.message, "SEP-$target vs pending SEP-$pendingProtocol")
      assertNull(memoOf(txn), "SEP-$target memo")
      assertNothingSaved()
    }
  }

  @Test
  fun test_handle_refuses_the_second_assignment_of_a_memo_without_any_database_constraint() {
    // Stores that remember what was saved and enforce no unique constraint of their own.
    val saved = mutableListOf<JdbcSep24Transaction>()
    val first = newWithdrawal("24", "tx-first") as JdbcSep24Transaction
    val second = newWithdrawal("24", "tx-second") as JdbcSep24Transaction
    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } answers
      {
        listOf(first, second).first { it.id == arg<String>(0) }
      }
    every { txn24Store.save(any()) } answers
      {
        saved.add(firstArg())
        null
      }
    every { txn24Store.findAllByWithdrawAnchorAccountAndMemoAndStatus(any(), any(), any()) } answers
      {
        saved.filter {
          it.withdrawAnchorAccount == arg<String>(0) &&
            it.memo == arg<String>(1) &&
            it.status == arg<String>(2)
        }
      }
    every { txn6Store.findAllByWithdrawAnchorAccountAndMemoAndStatus(any(), any(), any()) } returns
      emptyList()
    every { txn31Store.findAllByToAccountAndMemoAndStatus(any(), any(), any()) } returns emptyList()
    val handler = selfGeneratorHandler()

    handler.handle(selfGeneratorRequest("tx-first"))
    val ex = assertThrows<BadRequestException> { handler.handle(selfGeneratorRequest("tx-second")) }

    assertEquals(memoInUseMessage, ex.message)
    assertEquals(listOf("tx-first"), saved.map { it.id })
    assertEquals(PENDING_USR_TRANSFER_START.toString(), first.status)
    assertEquals(INCOMPLETE.toString(), second.status)
    assertNull(second.memo)
  }
}
