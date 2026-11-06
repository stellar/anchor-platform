package org.stellar.anchor.platform.rpc

import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind.*
import org.stellar.anchor.api.rpc.method.AmountAssetRequest
import org.stellar.anchor.api.rpc.method.NotifyRefundPendingRequest
import org.stellar.anchor.api.rpc.method.NotifyRefundSentRequest
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.Refunds
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
 * HackerOne #4028479 and #4141638 (ANCHOR-1329): a repeated refund.id is checked and stored with
 * the same replace-by-id arithmetic in notify_refund_sent and notify_refund_pending. Test names
 * start with the spec requirement id (rrNN) so a check selects them with `rrNN_*`.
 */
class RefundIdReplayTest {
  companion object {
    private const val TX_ID = "testId"
    private const val NATIVE = "stellar:native"
    private const val CAP_MESSAGE = "Refund amount exceeds amount_in"
  }

  /** A refund already stored on the transaction. */
  private data class Stored(val id: String, val amount: String, val fee: String = "0")

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

  private fun sentRequest(id: String, amount: String, fee: String = "0") =
    NotifyRefundSentRequest.builder()
      .transactionId(TX_ID)
      .refund(
        NotifyRefundSentRequest.Refund.builder()
          .id(id)
          .amount(AmountAssetRequest(amount, NATIVE))
          .amountFee(AmountAssetRequest(fee, NATIVE))
          .build()
      )
      .build()

  private fun pendingRequest(id: String, amount: String, fee: String = "0") =
    NotifyRefundPendingRequest.builder()
      .transactionId(TX_ID)
      .refund(
        NotifyRefundPendingRequest.Refund.builder()
          .id(id)
          .amountFee(AmountAssetRequest(fee, NATIVE))
          .amount(AmountAssetRequest(amount, NATIVE))
          .build()
      )
      .build()

  private fun total(stored: List<Stored>) =
    stored.fold(BigDecimal.ZERO) { acc, s -> acc + BigDecimal(s.amount) + BigDecimal(s.fee) }

  private fun feeTotal(stored: List<Stored>) =
    stored.fold(BigDecimal.ZERO) { acc, s -> acc + BigDecimal(s.fee) }

  /** A funded SEP-24 deposit whose stored refunds are exactly [stored] (null when empty). */
  private fun sep24Txn(
    amountIn: String = "100",
    stored: List<Stored> = emptyList(),
    status: SepTransactionStatus = PENDING_ANCHOR
  ): JdbcSep24Transaction {
    val txn = JdbcSep24Transaction()
    txn.status = status.toString()
    txn.kind = DEPOSIT.kind
    txn.transferReceivedAt = Instant.now()
    txn.amountIn = amountIn
    txn.amountInAsset = NATIVE
    txn.amountFee = "0"
    txn.amountFeeAsset = NATIVE
    if (stored.isNotEmpty()) {
      val refunds = JdbcSep24Refunds()
      refunds.payments =
        stored.map {
          JdbcSep24RefundPayment.builder()
            .id(it.id)
            .idType("EXTERNAL")
            .amount(it.amount)
            .fee(it.fee)
            .build()
        }
      refunds.amountRefunded = total(stored).toPlainString()
      refunds.amountFee = feeTotal(stored).toPlainString()
      txn.refunds = refunds
    }
    every { txn24Store.findByTransactionId(TX_ID) } returns txn
    return txn
  }

  private fun assertSep24Refunds(
    txn: JdbcSep24Transaction,
    amountRefunded: String,
    amountFee: String,
    ids: List<String>
  ) {
    assertEquals(amountRefunded, txn.refunds.amountRefunded)
    assertEquals(amountFee, txn.refunds.amountFee)
    assertEquals(ids, txn.refunds.refundPayments.map { it.id })
  }

  /** A funded SEP-6 transaction of [kind] whose stored refunds are exactly [stored]. */
  private fun sep6Txn(
    kind: String,
    amountIn: String = "100",
    stored: List<Stored> = emptyList(),
    status: SepTransactionStatus = PENDING_ANCHOR
  ): JdbcSep6Transaction {
    val txn = JdbcSep6Transaction()
    txn.status = status.toString()
    txn.kind = kind
    txn.transferReceivedAt = Instant.now()
    txn.amountIn = amountIn
    txn.amountInAsset = NATIVE
    txn.amountFee = "0"
    txn.amountFeeAsset = NATIVE
    if (stored.isNotEmpty()) {
      val refunds = Refunds()
      refunds.payments =
        stored
          .map {
            org.stellar.anchor.api.shared.RefundPayment.builder()
              .id(it.id)
              .idType(org.stellar.anchor.api.shared.RefundPayment.IdType.EXTERNAL)
              .amount(Amount(it.amount, NATIVE))
              .fee(Amount(it.fee, NATIVE))
              .build()
          }
          .toTypedArray()
      refunds.amountRefunded = Amount(total(stored).toPlainString(), NATIVE)
      refunds.amountFee = Amount(feeTotal(stored).toPlainString(), NATIVE)
      txn.refunds = refunds
    }
    every { txn6Store.findByTransactionId(TX_ID) } returns txn
    return txn
  }

  private fun assertSep6Refunds(
    txn: JdbcSep6Transaction,
    amountRefunded: String,
    amountFee: String,
    ids: List<String>
  ) {
    assertEquals(amountRefunded, txn.refunds.amountRefunded.amount)
    assertEquals(amountFee, txn.refunds.amountFee.amount)
    assertEquals(ids, txn.refunds.payments.map { it.id })
  }

  // ---- notify_refund_sent from pending_anchor, SEP-24 (RR-01 to RR-13, RR-24, RR-25) ----

  @Test
  fun rr01_sep24_replayOfStoredIdIsAcceptedAndTotalStays() {
    val txn = sep24Txn(stored = listOf(Stored("A", "40")))

    sentHandler.handle(sentRequest("A", "40"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "40", "0", listOf("A"))
  }

  @Test
  fun rr02_sep24_replayRepeated25TimesIsAcceptedEveryTime() {
    val txn = sep24Txn(stored = listOf(Stored("A", "40")))

    repeat(25) { sentHandler.handle(sentRequest("A", "40")) }

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "40", "0", listOf("A"))
  }

  @Test
  fun rr03_sep24_replayOfHalfAmountInDoesNotFlipToRefunded() {
    val txn = sep24Txn(stored = listOf(Stored("A", "50")))

    sentHandler.handle(sentRequest("A", "50"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "50", "0", listOf("A"))
  }

  @Test
  fun rr04_sep24_replayWithFeeCountsTheFeeOnce() {
    val txn = sep24Txn(stored = listOf(Stored("A", "40", "10")))

    sentHandler.handle(sentRequest("A", "40", "10"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "50", "10", listOf("A"))
  }

  @Test
  fun rr05_sep24_replacementAboveAmountInIsRejectedAndUnchanged() {
    val txn = sep24Txn(stored = listOf(Stored("A", "40")))

    val ex = assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("A", "101")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "40", "0", listOf("A"))
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @Test
  fun rr06_sep24_correctingStoredIdFrom60To70IsAccepted() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60")))

    sentHandler.handle(sentRequest("A", "70"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "70", "0", listOf("A"))
  }

  @Test
  fun rr07_sep24_newIdAboveRemainingAmountInIsRejectedAndUnchanged() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60")))

    val ex = assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("B", "50")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "60", "0", listOf("A"))
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @Test
  fun rr08_sep24_newIdFillingAmountInSetsRefundedWithTwoPayments() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60")))

    sentHandler.handle(sentRequest("B", "40"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertSep24Refunds(txn, "100", "0", listOf("A", "B"))
  }

  @Test
  fun rr09_sep24_replacingStoredIdUpToAmountInSetsRefunded() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60")))

    sentHandler.handle(sentRequest("A", "100"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertSep24Refunds(txn, "100", "0", listOf("A"))
  }

  @Test
  fun rr10_sep24_firstRefundOfAmountInSetsRefunded() {
    val txn = sep24Txn()

    sentHandler.handle(sentRequest("A", "100"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertSep24Refunds(txn, "100", "0", listOf("A"))
  }

  @Test
  fun rr11_sep24_firstRefundAboveAmountInIsRejected() {
    val txn = sep24Txn()

    val ex = assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("A", "101")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @Test
  fun rr12_sep24_storedListHoldingTheIdTwiceCollapsesToOnePayment() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60"), Stored("A", "60")))

    sentHandler.handle(sentRequest("A", "70"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "70", "0", listOf("A"))
  }

  @Test
  fun rr13_sep24_replacementEqualToAmountInAtSeventhDecimalSetsRefunded() {
    val txn = sep24Txn(amountIn = "12.3456789", stored = listOf(Stored("A", "12.3456788")))

    sentHandler.handle(sentRequest("A", "12.3456789"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertSep24Refunds(txn, "12.3456789", "0", listOf("A"))
  }

  @Test
  fun rr13_sep24_replacementAboveAmountInAtSeventhDecimalIsRejected() {
    val txn = sep24Txn(amountIn = "12.3456789", stored = listOf(Stored("A", "12.3456788")))

    val ex =
      assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("A", "12.3456790")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertSep24Refunds(txn, "12.3456788", "0", listOf("A"))
  }

  @Test
  fun rr24_sep24_cappedCallLeavesRefundsStatusAndStoreUntouched() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60", "5")))

    assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("B", "40", "5")) }

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "65", "5", listOf("A"))
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @Test
  fun rr25_sep24_unannouncedIdIsRejectedOutsidePendingAnchor() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60")), status = PENDING_EXTERNAL)

    val ex = assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("B", "10")) }

    assertEquals("Invalid refund id", ex.message)
    assertEquals(PENDING_EXTERNAL.toString(), txn.status)
    assertSep24Refunds(txn, "60", "0", listOf("A"))
  }

  @Test
  fun rr25_sep24_announcedIdKeepsReplaceByIdTotalOutsidePendingAnchor() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60")), status = PENDING_EXTERNAL)

    sentHandler.handle(sentRequest("A", "70"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "70", "0", listOf("A"))
  }

  // ---- notify_refund_sent from pending_anchor, SEP-6 (RR-01 to RR-13, RR-24, RR-25) ----

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr01_sep6_replayOfStoredIdIsAcceptedAndTotalStays(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "40")))

    sentHandler.handle(sentRequest("A", "40"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "40", "0", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr02_sep6_replayRepeated25TimesIsAcceptedEveryTime(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "40")))

    repeat(25) { sentHandler.handle(sentRequest("A", "40")) }

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "40", "0", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr03_sep6_replayOfHalfAmountInDoesNotFlipToRefunded(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "50")))

    sentHandler.handle(sentRequest("A", "50"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "50", "0", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr04_sep6_replayWithFeeCountsTheFeeOnce(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "40", "10")))

    sentHandler.handle(sentRequest("A", "40", "10"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "50", "10", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr05_sep6_replacementAboveAmountInIsRejectedAndUnchanged(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "40")))

    val ex = assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("A", "101")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "40", "0", listOf("A"))
    verify(exactly = 0) { txn6Store.save(any()) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr06_sep6_correctingStoredIdFrom60To70IsAccepted(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "60")))

    sentHandler.handle(sentRequest("A", "70"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "70", "0", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr07_sep6_newIdAboveRemainingAmountInIsRejectedAndUnchanged(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "60")))

    val ex = assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("B", "50")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "60", "0", listOf("A"))
    verify(exactly = 0) { txn6Store.save(any()) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr08_sep6_newIdFillingAmountInSetsRefundedWithTwoPayments(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "60")))

    sentHandler.handle(sentRequest("B", "40"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertSep6Refunds(txn, "100", "0", listOf("A", "B"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr09_sep6_replacingStoredIdUpToAmountInSetsRefunded(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "60")))

    sentHandler.handle(sentRequest("A", "100"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertSep6Refunds(txn, "100", "0", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr10_sep6_firstRefundOfAmountInSetsRefunded(kind: String) {
    val txn = sep6Txn(kind)

    sentHandler.handle(sentRequest("A", "100"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertSep6Refunds(txn, "100", "0", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr11_sep6_firstRefundAboveAmountInIsRejected(kind: String) {
    val txn = sep6Txn(kind)

    val ex = assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("A", "101")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    verify(exactly = 0) { txn6Store.save(any()) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr12_sep6_storedListHoldingTheIdTwiceCollapsesToOnePayment(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "60"), Stored("A", "60")))

    sentHandler.handle(sentRequest("A", "70"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "70", "0", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr13_sep6_replacementEqualToAmountInAtSeventhDecimalSetsRefunded(kind: String) {
    val txn = sep6Txn(kind, "12.3456789", listOf(Stored("A", "12.3456788")))

    sentHandler.handle(sentRequest("A", "12.3456789"))

    assertEquals(REFUNDED.toString(), txn.status)
    assertSep6Refunds(txn, "12.3456789", "0", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr13_sep6_replacementAboveAmountInAtSeventhDecimalIsRejected(kind: String) {
    val txn = sep6Txn(kind, "12.3456789", listOf(Stored("A", "12.3456788")))

    val ex =
      assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("A", "12.3456790")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertSep6Refunds(txn, "12.3456788", "0", listOf("A"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["deposit", "withdrawal"])
  fun rr24_sep6_cappedCallLeavesRefundsStatusAndStoreUntouched(kind: String) {
    val txn = sep6Txn(kind, stored = listOf(Stored("A", "60", "5")))

    assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("B", "40", "5")) }

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "65", "5", listOf("A"))
    verify(exactly = 0) { txn6Store.save(any()) }
  }

  @Test
  fun rr25_sep6_unannouncedIdIsRejectedOutsidePendingAnchor() {
    val txn = sep6Txn(DEPOSIT.kind, stored = listOf(Stored("A", "60")), status = PENDING_EXTERNAL)

    val ex = assertThrows<InvalidParamsException> { sentHandler.handle(sentRequest("B", "10")) }

    assertEquals("Invalid refund id", ex.message)
    assertEquals(PENDING_EXTERNAL.toString(), txn.status)
    assertSep6Refunds(txn, "60", "0", listOf("A"))
  }

  @Test
  fun rr25_sep6_announcedIdKeepsReplaceByIdTotalOutsidePendingAnchor() {
    val txn = sep6Txn(DEPOSIT.kind, stored = listOf(Stored("A", "60")), status = PENDING_EXTERNAL)

    sentHandler.handle(sentRequest("A", "70"))

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "70", "0", listOf("A"))
  }

  // ---- notify_refund_pending replaces a repeated refund id (RR-14, RR-15, RR-18) ----

  @Test
  fun rr14_sep24_pendingWithStoredIdReplacesItAndListsItOnce() {
    val txn = sep24Txn(stored = listOf(Stored("A", "30")))

    pendingHandler.handle(pendingRequest("A", "20"))

    assertEquals(PENDING_EXTERNAL.toString(), txn.status)
    assertSep24Refunds(txn, "20", "0", listOf("A"))
  }

  @Test
  fun rr14_sep6_pendingWithStoredIdReplacesItAndListsItOnce() {
    val txn = sep6Txn(DEPOSIT.kind, stored = listOf(Stored("A", "30")))

    pendingHandler.handle(pendingRequest("A", "20"))

    assertEquals(PENDING_EXTERNAL.toString(), txn.status)
    assertSep6Refunds(txn, "20", "0", listOf("A"))
  }

  @Test
  fun rr15_sep24_replayedPendingKeepsOneRefundWorthOfAmounts() {
    val txn = sep24Txn(amountIn = "1")

    pendingHandler.handle(pendingRequest("A", "0.4", "0.01"))
    assertSep24Refunds(txn, "0.41", "0.01", listOf("A"))
    txn.status = PENDING_ANCHOR.toString()
    pendingHandler.handle(pendingRequest("A", "0.4", "0.01"))

    assertSep24Refunds(txn, "0.41", "0.01", listOf("A"))
  }

  @Test
  fun rr15_sep6_replayedPendingKeepsOneRefundWorthOfAmounts() {
    val txn = sep6Txn(DEPOSIT.kind, amountIn = "1")

    pendingHandler.handle(pendingRequest("A", "0.4", "0.01"))
    assertSep6Refunds(txn, "0.41", "0.01", listOf("A"))
    txn.status = PENDING_ANCHOR.toString()
    pendingHandler.handle(pendingRequest("A", "0.4", "0.01"))

    assertSep6Refunds(txn, "0.41", "0.01", listOf("A"))
  }

  @Test
  fun rr18_sep24_pendingWithNewIdIsAppendedBesideTheStoredOne() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60")))

    pendingHandler.handle(pendingRequest("B", "30"))

    assertSep24Refunds(txn, "90", "0", listOf("A", "B"))
  }

  @Test
  fun rr18_sep6_pendingWithNewIdIsAppendedBesideTheStoredOne() {
    val txn = sep6Txn(DEPOSIT.kind, stored = listOf(Stored("A", "60")))

    pendingHandler.handle(pendingRequest("B", "30"))

    assertSep6Refunds(txn, "90", "0", listOf("A", "B"))
  }

  // ---- notify_refund_pending cap check over the replaced list (RR-16, RR-17, RR-24) ----

  @Test
  fun rr16_sep24_pendingCorrectingStoredIdFrom60To70IsAccepted() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60")))

    pendingHandler.handle(pendingRequest("A", "70"))

    assertEquals(PENDING_EXTERNAL.toString(), txn.status)
    assertSep24Refunds(txn, "70", "0", listOf("A"))
  }

  @Test
  fun rr16_sep6_pendingCorrectingStoredIdFrom60To70IsAccepted() {
    val txn = sep6Txn(DEPOSIT.kind, stored = listOf(Stored("A", "60")))

    pendingHandler.handle(pendingRequest("A", "70"))

    assertEquals(PENDING_EXTERNAL.toString(), txn.status)
    assertSep6Refunds(txn, "70", "0", listOf("A"))
  }

  @Test
  fun rr17_sep24_pendingNewIdAboveRemainingAmountInIsRejectedAndUnchanged() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60")))

    val ex =
      assertThrows<InvalidParamsException> { pendingHandler.handle(pendingRequest("B", "50")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "60", "0", listOf("A"))
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @Test
  fun rr17_sep6_pendingNewIdAboveRemainingAmountInIsRejectedAndUnchanged() {
    val txn = sep6Txn(DEPOSIT.kind, stored = listOf(Stored("A", "60")))

    val ex =
      assertThrows<InvalidParamsException> { pendingHandler.handle(pendingRequest("B", "50")) }

    assertEquals(CAP_MESSAGE, ex.message)
    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "60", "0", listOf("A"))
    verify(exactly = 0) { txn6Store.save(any()) }
  }

  @Test
  fun rr24_sep24_pendingCappedCallLeavesRefundsStatusAndStoreUntouched() {
    val txn = sep24Txn(stored = listOf(Stored("A", "60", "5")))

    assertThrows<InvalidParamsException> { pendingHandler.handle(pendingRequest("B", "40", "5")) }

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep24Refunds(txn, "65", "5", listOf("A"))
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @Test
  fun rr24_sep6_pendingCappedCallLeavesRefundsStatusAndStoreUntouched() {
    val txn = sep6Txn(DEPOSIT.kind, stored = listOf(Stored("A", "60", "5")))

    assertThrows<InvalidParamsException> { pendingHandler.handle(pendingRequest("B", "40", "5")) }

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertSep6Refunds(txn, "65", "5", listOf("A"))
    verify(exactly = 0) { txn6Store.save(any()) }
  }
}
