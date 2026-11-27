package org.stellar.anchor.platform.rpc

import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.WITHDRAWAL
import org.stellar.anchor.api.rpc.method.AmountAssetRequest
import org.stellar.anchor.api.rpc.method.NotifyOnchainFundsReceivedRequest
import org.stellar.anchor.api.rpc.method.NotifyRefundSentRequest
import org.stellar.anchor.api.rpc.method.NotifyTransactionRecoveryRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.ledger.LedgerClient
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep38Quote
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.data.JdbcSepTransaction
import org.stellar.anchor.platform.observer.stellar.SacToAssetMapper
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep38.Sep38QuoteStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.MemoHelper
import org.stellar.sdk.Asset
import org.stellar.sdk.Memo
import org.stellar.sdk.xdr.OperationType

/**
 * What the business server can do with a payment held for arriving after its firm quote expired
 * (spec QEXP-14, QEXP-15): the real handlers run one after another on the same transaction object,
 * as the store would hand it back.
 */
class LateQuotePaymentFlowTest {
  companion object {
    private const val TX_ID = "testId"
    private const val QUOTE_ID = "q1"
    private const val STELLAR_TX_ID = "stellarTxId"
    private const val FIAT_USD = "iso4217:USD"
    private const val STELLAR_USDC =
      "stellar:USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val USDC_CODE_ISSUER =
      "USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val ACCOUNT = "testTo"
    private val quoteExpiresAt = Instant.parse("2026-11-19T12:43:39Z")
    private val lateLedgerTime = Instant.parse("2026-11-19T12:44:08Z")
  }

  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore
  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore
  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore
  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator
  @MockK(relaxed = true) private lateinit var ledgerClient: LedgerClient
  @MockK(relaxed = true) private lateinit var eventService: EventService
  @MockK(relaxed = true) private lateinit var metricsService: MetricsService
  @MockK(relaxed = true) private lateinit var eventSession: Session
  @MockK(relaxed = true) private lateinit var sepTransactionCounter: Counter
  @MockK(relaxed = true) private lateinit var sep38QuoteStore: Sep38QuoteStore
  @MockK(relaxed = true) private lateinit var rpcConfig: RpcConfig

  private lateinit var received: NotifyOnchainFundsReceivedHandler
  private lateinit var recovery: NotifyTransactionRecoveryHandler
  private lateinit var refundSent: NotifyRefundSentHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    every { metricsService.counter(any(), any(), any()) } returns sepTransactionCounter
    val assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    received =
      NotifyOnchainFundsReceivedHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        ledgerClient,
        assetService,
        eventService,
        metricsService,
        mockk<SacToAssetMapper>(),
        sep38QuoteStore,
        rpcConfig
      )
    recovery =
      NotifyTransactionRecoveryHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService
      )
    refundSent =
      NotifyRefundSentHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService
      )
    every { sep38QuoteStore.findByQuoteId(QUOTE_ID) } returns
      JdbcSep38Quote().also {
        it.id = QUOTE_ID
        it.expiresAt = quoteExpiresAt
      }
    every { ledgerClient.getTransaction(STELLAR_TX_ID) } returns
      LedgerTransaction.builder()
        .hash(STELLAR_TX_ID)
        .ledger(1L)
        .applicationOrder(1)
        .memo(MemoHelper.toXdr(Memo.id(12345)))
        .sourceAccount("testFrom")
        .createdAt(lateLedgerTime)
        .fee(100)
        .envelopeXdr("testEnvelopeXdr")
        .operations(
          listOf(
            LedgerOperation.builder()
              .type(OperationType.PAYMENT)
              .paymentOperation(
                LedgerPaymentOperation.builder()
                  .id("12345")
                  .amount(BigInteger.valueOf(100000000))
                  .asset(Asset.create(USDC_CODE_ISSUER).toXdr())
                  .from("testFrom")
                  .to(ACCOUNT)
                  .build()
              )
              .build()
          )
        )
        .build()
  }

  /**
   * A transaction bound to a quote that expired before [lateLedgerTime], waiting for the payment.
   */
  private fun awaitingPayment(protocol: String): JdbcSepTransaction {
    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    return when (protocol) {
      "24" ->
        JdbcSep24Transaction().also {
          it.id = TX_ID
          it.status = PENDING_USR_TRANSFER_START.toString()
          it.kind = WITHDRAWAL.kind
          it.withdrawAnchorAccount = ACCOUNT
          it.memo = "12345"
          it.memoType = "id"
          it.amountIn = "10"
          it.amountInAsset = STELLAR_USDC
          it.amountFeeAsset = FIAT_USD
          it.quoteId = QUOTE_ID
          every { txn24Store.findByTransactionId(TX_ID) } returns it
        }
      "6" ->
        JdbcSep6Transaction().also {
          it.id = TX_ID
          it.status = PENDING_USR_TRANSFER_START.toString()
          it.kind = WITHDRAWAL.kind
          it.withdrawAnchorAccount = ACCOUNT
          it.memo = "12345"
          it.memoType = "id"
          it.amountIn = "10"
          it.amountInAsset = STELLAR_USDC
          it.amountFeeAsset = FIAT_USD
          it.quoteId = QUOTE_ID
          every { txn6Store.findByTransactionId(TX_ID) } returns it
        }
      else ->
        JdbcSep31Transaction().also {
          it.id = TX_ID
          it.status = PENDING_SENDER.toString()
          it.toAccount = ACCOUNT
          it.stellarMemo = "12345"
          it.stellarMemoType = "id"
          it.amountIn = "10"
          it.amountInAsset = STELLAR_USDC
          it.amountFeeAsset = FIAT_USD
          it.quoteId = QUOTE_ID
          every { txn31Store.findByTransactionId(TX_ID) } returns it
        }
    }
  }

  private fun holdLatePayment(protocol: String): JdbcSepTransaction {
    val txn = awaitingPayment(protocol)
    received.handle(
      NotifyOnchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .stellarTransactionId(STELLAR_TX_ID)
        .build()
    )
    assertEquals(ERROR.toString(), txn.status)
    return txn
  }

  private fun refundAll() =
    NotifyRefundSentRequest.builder()
      .transactionId(TX_ID)
      .refund(
        NotifyRefundSentRequest.Refund.builder()
          .id("1")
          .amount(AmountAssetRequest("10", STELLAR_USDC))
          .amountFee(AmountAssetRequest("0", FIAT_USD))
          .build()
      )
      .build()

  private fun recover() =
    recovery.handle(NotifyTransactionRecoveryRequest.builder().transactionId(TX_ID).build())

  @CsvSource(value = ["24,pending_anchor", "6,pending_anchor", "31,pending_receiver"])
  @ParameterizedTest
  fun held_late_payment_is_recovered_to_the_normal_pending_status(
    protocol: String,
    expectedStatus: String
  ) {
    val txn = holdLatePayment(protocol)

    recover()

    assertEquals(expectedStatus, txn.status)
  }

  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun refund_sent_is_accepted_after_recovery(protocol: String) {
    val txn = holdLatePayment(protocol)
    recover()

    refundSent.handle(refundAll())

    assertEquals(REFUNDED.toString(), txn.status)
  }

  @CsvSource(value = ["24", "6", "31"])
  @ParameterizedTest
  fun refund_sent_is_rejected_while_the_payment_is_held(protocol: String) {
    holdLatePayment(protocol)

    val ex = assertThrows<InvalidRequestException> { refundSent.handle(refundAll()) }

    assertContains(ex.message!!, "Status[error]")
  }
}
