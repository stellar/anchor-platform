package org.stellar.anchor.platform.rpc

import com.google.gson.reflect.TypeToken
import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.math.BigInteger
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
import org.stellar.anchor.api.exception.LedgerException
import org.stellar.anchor.api.exception.rpc.InternalErrorException
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.DEPOSIT
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.*
import org.stellar.anchor.api.rpc.method.NotifyOnchainFundsSentRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.Customers
import org.stellar.anchor.api.shared.StellarId
import org.stellar.anchor.api.shared.StellarTransaction
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.data.JdbcSepTransaction
import org.stellar.anchor.platform.observer.stellar.SacToAssetMapper
import org.stellar.anchor.platform.service.AnchorMetrics.PLATFORM_RPC_TRANSACTION
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.MemoHelper
import org.stellar.sdk.Memo
import org.stellar.sdk.xdr.Asset
import org.stellar.sdk.xdr.OperationType

class NotifyOnchainFundsSentHandlerTest {

  companion object {
    private const val TX_ID = "testId"
    private const val STELLAR_TX_ID = "stellarTxId"
    private const val VALIDATION_ERROR_MESSAGE = "Invalid request"
    private const val TO_ACCOUNT = "testTo"
    private const val USDC_CODE_ISSUER =
      "USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val STELLAR_USDC = "stellar:$USDC_CODE_ISSUER"

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
        .sourceAccount("testSourceAccount")
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

  @MockK(relaxed = true) private lateinit var horizon: Horizon

  @MockK(relaxed = true) private lateinit var eventService: EventService

  @MockK(relaxed = true) private lateinit var metricsService: MetricsService

  @MockK(relaxed = true) private lateinit var eventSession: Session

  @MockK(relaxed = true) private lateinit var sepTransactionCounter: Counter

  private lateinit var handler: NotifyOnchainFundsSentHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    this.handler =
      NotifyOnchainFundsSentHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        horizon,
        assetService,
        eventService,
        metricsService,
        mockk<SacToAssetMapper>()
      )
  }

  @Test
  fun test_handle_unsupportedProtocol() {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_STELLAR.toString()
    val spyTxn24 = spyk(txn24)

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns spyTxn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { spyTxn24.protocol } returns SEP_38.sep.toString()

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[pending_stellar], kind[null], protocol[38], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_invalidRequest() {
    val transferReceivedAt = Instant.now()
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt

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
  fun test_handle_invalidStellarTransaction() {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyOnchainFundsSentRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.transferReceivedAt = transferReceivedAt
    val sep24TxnCapture = slot<JdbcSep24Transaction>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { horizon.getTransaction(any()) } throws LedgerException("Invalid stellar transaction")

    val ex = assertThrows<InternalErrorException> { handler.handle(request) }
    assertEquals(
      "Failed to retrieve Stellar transaction by ID[stellarTxId]: Invalid stellar transaction",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedStatus() {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = INCOMPLETE.toString()
    txn24.kind = DEPOSIT.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[incomplete], kind[deposit], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_unsupportedKind() {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_STELLAR.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[pending_stellar], kind[withdrawal], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_transferNotReceived() {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = WITHDRAWAL.kind

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[pending_anchor], kind[withdrawal], protocol[24], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @Test
  fun test_handle_sep24_ok() {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyOnchainFundsSentRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn24 = JdbcSep24Transaction()
    txn24.status = PENDING_ANCHOR.toString()
    txn24.kind = DEPOSIT.kind
    txn24.toAccount = TO_ACCOUNT
    txn24.amountOutAsset = STELLAR_USDC
    txn24.transferReceivedAt = transferReceivedAt
    txn24.userActionRequiredBy = Instant.now()
    val sep24TxnCapture = slot<JdbcSep24Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(TX_ID) } returns txn24
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn24Store.save(capture(sep24TxnCapture)) } returns null
    every { horizon.getTransaction(STELLAR_TX_ID) } returns testLedgerTxn
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
    expectedSep24Txn.toAccount = TO_ACCOUNT
    expectedSep24Txn.amountOutAsset = STELLAR_USDC
    expectedSep24Txn.status = COMPLETED.toString()
    expectedSep24Txn.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedSep24Txn.completedAt = sep24TxnCapture.captured.completedAt
    expectedSep24Txn.transferReceivedAt = transferReceivedAt
    expectedSep24Txn.stellarTransactionId = STELLAR_TX_ID
    expectedSep24Txn.stellarTransactions = stellarTransactions

    JSONAssert.assertEquals(
      gson.toJson(expectedSep24Txn),
      gson.toJson(sep24TxnCapture.captured),
      JSONCompareMode.STRICT
    )

    val expectedResponse = GetTransactionResponse()
    expectedResponse.sep = SEP_24
    expectedResponse.kind = DEPOSIT
    expectedResponse.status = COMPLETED
    expectedResponse.destinationAccount = TO_ACCOUNT
    expectedResponse.updatedAt = sep24TxnCapture.captured.updatedAt
    expectedResponse.completedAt = sep24TxnCapture.captured.completedAt
    expectedResponse.amountExpected = Amount(null, "")
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
    assertTrue(sep24TxnCapture.captured.completedAt >= startDate)
    assertTrue(sep24TxnCapture.captured.completedAt <= endDate)
  }

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_unsupportedStatus(kind: String) {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = INCOMPLETE.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[incomplete], kind[$kind], protocol[6], funds received[false]",
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
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_STELLAR.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[pending_stellar], kind[$kind], protocol[6], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_transferNotReceived(kind: String) {
    val request = NotifyOnchainFundsSentRequest.builder().transactionId(TX_ID).build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = kind

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals(
      "RPC method[notify_onchain_funds_sent] is not supported. Status[pending_anchor], kind[$kind], protocol[6], funds received[false]",
      ex.message
    )

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
  }

  @CsvSource(value = ["deposit", "deposit-exchange"])
  @ParameterizedTest
  fun test_handle_sep6_ok(kind: String) {
    val transferReceivedAt = Instant.now()
    val request =
      NotifyOnchainFundsSentRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_ANCHOR.toString()
    txn6.kind = kind
    txn6.toAccount = TO_ACCOUNT
    txn6.amountOutAsset = STELLAR_USDC
    txn6.transferReceivedAt = transferReceivedAt
    val sep6TxnCapture = slot<JdbcSep6Transaction>()
    val anchorEventCapture = slot<AnchorEvent>()

    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    every { txn6Store.save(capture(sep6TxnCapture)) } returns null
    every { horizon.getTransaction(STELLAR_TX_ID) } returns testLedgerTxn
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
    expectedSep6Txn.toAccount = TO_ACCOUNT
    expectedSep6Txn.amountOutAsset = STELLAR_USDC
    expectedSep6Txn.status = COMPLETED.toString()
    expectedSep6Txn.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedSep6Txn.completedAt = sep6TxnCapture.captured.completedAt
    expectedSep6Txn.transferReceivedAt = transferReceivedAt
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
    expectedResponse.status = COMPLETED
    expectedResponse.destinationAccount = TO_ACCOUNT
    expectedResponse.updatedAt = sep6TxnCapture.captured.updatedAt
    expectedResponse.transferReceivedAt = transferReceivedAt
    expectedResponse.completedAt = sep6TxnCapture.captured.completedAt
    expectedResponse.amountExpected = Amount(null, "")
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
    assertTrue(sep6TxnCapture.captured.completedAt >= startDate)
    assertTrue(sep6TxnCapture.captured.completedAt <= endDate)
  }

  // ---- The hash must be a payout to the recipient (spec SENT-01..07) ---------------------------

  private val usdcXdr = org.stellar.sdk.Asset.create(USDC_CODE_ISSUER).toXdr()
  private val eurcXdr =
    org.stellar.sdk.Asset.create("EURC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN")
      .toXdr()
  private val receivedAt = Instant.parse("2023-05-09T09:00:00Z")

  /** A deposit that received the user's funds and now waits for the payout hash. */
  private fun stubDeposit(
    protocol: String,
    kind: String,
    toAccount: String = TO_ACCOUNT
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
    return if (protocol == "24") {
      JdbcSep24Transaction().also {
        it.id = TX_ID
        it.status = PENDING_ANCHOR.toString()
        it.kind = kind
        it.transferReceivedAt = receivedAt
        it.toAccount = toAccount
        it.memo = "99"
        it.memoType = "id"
        it.amountOutAsset = STELLAR_USDC
        every { txn24Store.findByTransactionId(TX_ID) } returns it
      }
    } else {
      JdbcSep6Transaction().also {
        it.id = TX_ID
        it.status = PENDING_ANCHOR.toString()
        it.kind = kind
        it.transferReceivedAt = receivedAt
        it.toAccount = toAccount
        it.memo = "99"
        it.memoType = "id"
        it.amountOutAsset = STELLAR_USDC
        every { txn6Store.findByTransactionId(TX_ID) } returns it
      }
    }
  }

  private fun payout(
    to: String = TO_ACCOUNT,
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
      .sourceAccount("testSourceAccount")
      .createdAt(Instant.parse("2023-05-10T10:18:20Z"))
      .fee(100)
      .envelopeXdr("testEnvelopeXdr")
      .operations(operations.toList())
      .build()

  private fun sentRequest() =
    NotifyOnchainFundsSentRequest.builder()
      .transactionId(TX_ID)
      .stellarTransactionId(STELLAR_TX_ID)
      .build()

  private fun rejection(ledgerTxn: LedgerTransaction): InvalidParamsException {
    every { horizon.getTransaction(STELLAR_TX_ID) } returns ledgerTxn
    return assertThrows<InvalidParamsException> { handler.handle(sentRequest()) }
  }

  private fun accept(ledgerTxn: LedgerTransaction) {
    every { horizon.getTransaction(STELLAR_TX_ID) } returns ledgerTxn
    handler.handle(sentRequest())
  }

  @CsvSource(value = ["24,deposit", "6,deposit", "6,deposit-exchange"])
  @ParameterizedTest
  fun test_handle_rejects_payout_to_other_account(protocol: String, kind: String) {
    stubDeposit(protocol, kind)

    val ex = rejection(ledgerOf(null, payout(to = "someoneElse")))

    assertTrue(ex.message!!.contains("destination"), ex.message)
    assertTrue(ex.message!!.contains(STELLAR_TX_ID), ex.message)
  }

  @CsvSource(value = ["24,deposit", "6,deposit", "6,deposit-exchange"])
  @ParameterizedTest
  fun test_handle_rejects_payout_in_other_asset(protocol: String, kind: String) {
    stubDeposit(protocol, kind)

    val ex = rejection(ledgerOf(null, payout(asset = eurcXdr)))

    assertTrue(ex.message!!.contains("asset"), ex.message)
    assertTrue(ex.message!!.contains(STELLAR_TX_ID), ex.message)
  }

  @CsvSource(value = ["24,deposit", "6,deposit", "6,deposit-exchange"])
  @ParameterizedTest
  fun test_handle_rejects_payout_with_zero_amount(protocol: String, kind: String) {
    stubDeposit(protocol, kind)

    val ex = rejection(ledgerOf(null, payout(amount = 0)))

    assertTrue(ex.message!!.contains("amount"), ex.message)
    assertTrue(ex.message!!.contains(STELLAR_TX_ID), ex.message)
  }

  @CsvSource(value = ["24,deposit", "6,deposit", "6,deposit-exchange"])
  @ParameterizedTest
  fun test_handle_accepts_payout_with_any_memo(protocol: String, kind: String) {
    for (memo in listOf(null, Memo.text("anything"), Memo.id(99), Memo.id(7))) {
      val txn = stubDeposit(protocol, kind)

      accept(ledgerOf(memo, payout()))

      assertEquals(COMPLETED.toString(), txn.status)
      assertEquals(receivedAt, txn.transferReceivedAt)
      assertEquals(STELLAR_TX_ID, txn.stellarTransactionId)
    }
  }

  @CsvSource(value = ["24,deposit", "6,deposit"])
  @ParameterizedTest
  fun test_handle_accepts_payout_to_muxed_form_of_to_account(protocol: String, kind: String) {
    val account = "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    val muxed = org.stellar.sdk.MuxedAccount(account, BigInteger.valueOf(42)).address

    val toBase = stubDeposit(protocol, kind, account)
    accept(ledgerOf(null, payout(to = muxed)))
    assertEquals(COMPLETED.toString(), toBase.status)

    val toMuxed = stubDeposit(protocol, kind, muxed)
    accept(ledgerOf(null, payout(to = muxed)))
    assertEquals(COMPLETED.toString(), toMuxed.status)
  }

  private fun rejectedPayout(cause: String): LedgerTransaction =
    when (cause) {
      "destination" -> ledgerOf(null, payout(to = "someoneElse"))
      "asset" -> ledgerOf(null, payout(asset = eurcXdr))
      else -> ledgerOf(null, payout(amount = 0))
    }

  @CsvSource(
    value =
      [
        "24,deposit,destination",
        "24,deposit,asset",
        "24,deposit,amount",
        "6,deposit,destination",
        "6,deposit,asset",
        "6,deposit,amount",
        "6,deposit-exchange,destination",
        "6,deposit-exchange,asset",
        "6,deposit-exchange,amount"
      ]
  )
  @ParameterizedTest
  fun test_handle_rejected_payout_changes_nothing(protocol: String, kind: String, cause: String) {
    val txn = stubDeposit(protocol, kind)
    val before = gson.toJson(txn)

    rejection(rejectedPayout(cause))

    assertEquals(before, gson.toJson(txn))
    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
    verify(exactly = 0) { sepTransactionCounter.increment() }
    verify(exactly = 0) { eventSession.publish(any()) }
  }
}

private const val stellarTransactionRecord =
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

private const val paymentOperationRecord =
  """
[
  {
    "type": "PAYMENT",
    "paymentOperation": {
      "assetType": "native",
      "sourceAccount": "testSourceAccount",
      "from": "testFrom",
      "to": "testTo",
      "amount": "15.0000000",
      "asset": {
        "discriminant": "ASSET_TYPE_NATIVE"
      }
    }
  }
]  
"""
