package org.stellar.anchor.platform.rpc

import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.*
import org.stellar.anchor.api.rpc.method.AmountAssetRequest
import org.stellar.anchor.api.rpc.method.NotifyRefundPendingRequest
import org.stellar.anchor.api.rpc.method.NotifyRefundSentRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.*
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore

/**
 * HackerOne #4072993: refund totals are persisted at the asset's significant decimals (7 for
 * stellar:native), so the refund_sent comparison against amount_in is exact.
 */
class RefundPrecisionTest {
  companion object {
    private const val TX_ID = "testId"
    private const val NATIVE = "stellar:native"
  }

  @MockK(relaxed = true) private lateinit var txn6Store: Sep6TransactionStore
  @MockK(relaxed = true) private lateinit var txn24Store: Sep24TransactionStore
  @MockK(relaxed = true) private lateinit var txn31Store: Sep31TransactionStore
  @MockK(relaxed = true) private lateinit var requestValidator: RequestValidator
  @MockK(relaxed = true) private lateinit var eventService: EventService
  @MockK(relaxed = true) private lateinit var metricsService: MetricsService
  @MockK(relaxed = true) private lateinit var eventSession: Session
  @MockK(relaxed = true) private lateinit var counter: Counter

  private lateinit var pendingHandler: NotifyRefundPendingHandler
  private lateinit var sentHandler: NotifyRefundSentHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    every { eventSession.publish(any<AnchorEvent>()) } just Runs
    every { metricsService.counter(any(), any(), any()) } returns counter
    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null
    val assetService = DefaultAssetService.fromJsonResource("test_assets_native_7_decimals.json")
    pendingHandler =
      NotifyRefundPendingHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService
      )
    sentHandler =
      NotifyRefundSentHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService
      )
  }

  private fun pendingRequest(amount: String, fee: String = "0") =
    NotifyRefundPendingRequest.builder()
      .transactionId(TX_ID)
      .refund(
        NotifyRefundPendingRequest.Refund.builder()
          .id("refund-1")
          .amount(AmountAssetRequest(amount, NATIVE))
          .amountFee(AmountAssetRequest(fee, NATIVE))
          .build()
      )
      .build()

  private fun sentRequest(amount: String? = null, fee: String = "0") =
    NotifyRefundSentRequest.builder()
      .transactionId(TX_ID)
      .refund(
        amount?.let {
          NotifyRefundSentRequest.Refund.builder()
            .id("refund-1")
            .amount(AmountAssetRequest(it, NATIVE))
            .amountFee(AmountAssetRequest(fee, NATIVE))
            .build()
        }
      )
      .build()

  private fun sep24Txn(kind: String, amountIn: String): JdbcSep24Transaction {
    val txn = JdbcSep24Transaction()
    txn.status = PENDING_ANCHOR.toString()
    txn.kind = kind
    txn.transferReceivedAt = Instant.now()
    txn.amountIn = amountIn
    txn.amountInAsset = NATIVE
    txn.amountFee = "0"
    txn.amountFeeAsset = NATIVE
    every { txn24Store.findByTransactionId(TX_ID) } returns txn
    return txn
  }

  private fun sep6Txn(kind: String, amountIn: String): JdbcSep6Transaction {
    val txn = JdbcSep6Transaction()
    txn.status = PENDING_ANCHOR.toString()
    txn.kind = kind
    txn.transferReceivedAt = Instant.now()
    txn.amountIn = amountIn
    txn.amountInAsset = NATIVE
    txn.amountFee = "0"
    txn.amountFeeAsset = NATIVE
    every { txn6Store.findByTransactionId(TX_ID) } returns txn
    return txn
  }

  @ParameterizedTest
  @CsvSource(value = ["12.3456789", "12.34564", "12.3456"])
  fun `sep24 deposit full refund reaches refunded and persists the exact total`(amount: String) {
    val txn = sep24Txn(DEPOSIT.kind, amount)

    pendingHandler.handle(pendingRequest(amount))
    assertEquals(PENDING_EXTERNAL.toString(), txn.status)
    assertEquals(amount, txn.refunds.amountRefunded)
    assertEquals("0", txn.refunds.amountFee)

    sentHandler.handle(sentRequest())
    assertEquals(REFUNDED.toString(), txn.status)
    assertEquals(amount, txn.refunds.amountRefunded)
  }

  @ParameterizedTest
  @CsvSource(value = ["12.3456789", "12.34564", "12.3456"])
  fun `sep6 deposit full refund reaches refunded and persists the exact total`(amount: String) {
    val txn = sep6Txn(DEPOSIT.kind, amount)

    pendingHandler.handle(pendingRequest(amount))
    assertEquals(PENDING_EXTERNAL.toString(), txn.status)
    assertEquals(amount, txn.refunds.amountRefunded.amount)
    assertEquals("0", txn.refunds.amountFee.amount)

    sentHandler.handle(sentRequest())
    assertEquals(REFUNDED.toString(), txn.status)
    assertEquals(amount, txn.refunds.amountRefunded.amount)
  }

  @Test
  fun `sep24 withdrawal full refund in one call persists the exact total`() {
    val txn = sep24Txn(WITHDRAWAL.kind, "12.3456789")

    sentHandler.handle(sentRequest("12.3456789"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertEquals("12.3456789", txn.refunds.amountRefunded)
  }

  @Test
  fun `sep6 withdrawal full refund in one call persists the exact total`() {
    val txn = sep6Txn(WITHDRAWAL.kind, "12.3456789")

    sentHandler.handle(sentRequest("12.3456789"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertEquals("12.3456789", txn.refunds.amountRefunded.amount)
  }

  @Test
  fun `refund above amount_in at the asset precision is rejected and leaves the transaction`() {
    val txn = sep24Txn(DEPOSIT.kind, "12.3456789")

    val ex =
      assertThrows<InvalidParamsException> { pendingHandler.handle(pendingRequest("12.3456790")) }

    assertEquals("Refund amount exceeds amount_in", ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertEquals(null, txn.refunds)
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @Test
  fun `refund below amount_in at the asset precision stays pending_anchor after refund_sent`() {
    val txn = sep24Txn(DEPOSIT.kind, "12.3456789")

    pendingHandler.handle(pendingRequest("12.3456788"))
    sentHandler.handle(sentRequest())

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertEquals("12.3456788", txn.refunds.amountRefunded)
  }

  @Test
  fun `sep24 refund total sums two payments with fees at full precision`() {
    val txn = sep24Txn(DEPOSIT.kind, "12.3456789")

    pendingHandler.handle(pendingRequest("5.1234567", "0.0000001"))
    assertEquals("5.1234568", txn.refunds.amountRefunded)
    assertEquals("0.0000001", txn.refunds.amountFee)
  }

  @Test
  fun `sep6 deposit refund with a fee persists the exact fee and total at full precision`() {
    val txn = sep6Txn(DEPOSIT.kind, "12.3456789")

    pendingHandler.handle(pendingRequest("12.3456788", "0.0000001"))
    assertEquals("0.0000001", txn.refunds.amountFee.amount)
    assertEquals("12.3456789", txn.refunds.amountRefunded.amount)

    sentHandler.handle(sentRequest())
    assertEquals(REFUNDED.toString(), txn.status)
  }

  @Test
  fun `sep6 withdrawal refund with a fee persists the exact fee and total at full precision`() {
    val txn = sep6Txn(WITHDRAWAL.kind, "12.3456789")

    sentHandler.handle(sentRequest("12.3456788", "0.0000001"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertEquals("0.0000001", txn.refunds.amountFee.amount)
    assertEquals("12.3456789", txn.refunds.amountRefunded.amount)
  }

  @Test
  fun `refund_sent with a refund above amount_in at the asset precision is rejected`() {
    val txn = sep24Txn(WITHDRAWAL.kind, "12.3456789")

    val ex = assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("12.3456790")) }

    assertEquals("Refund amount exceeds amount_in", ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @Test
  fun `sep31 refund with a fee persists the exact fee and total and reaches refunded`() {
    val txn = JdbcSep31Transaction()
    txn.status = PENDING_RECEIVER.toString()
    txn.amountIn = "10.1234567"
    txn.amountInAsset = NATIVE
    txn.amountFee = "0"
    txn.amountFeeAsset = NATIVE
    every { txn31Store.findByTransactionId(TX_ID) } returns txn

    sentHandler.handle(sentRequest("10.1234566", "0.0000001"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertEquals("0.0000001", txn.refunds.amountFee)
    assertEquals("10.1234567", txn.refunds.amountRefunded)
  }
}
