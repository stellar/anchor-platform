package org.stellar.anchor.platform.rpc

import io.micrometer.core.instrument.Counter
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.rpc.method.NotifyOffchainFundsAvailableRequest
import org.stellar.anchor.api.rpc.method.NotifyOffchainFundsReceivedRequest
import org.stellar.anchor.api.rpc.method.NotifyOffchainFundsSentRequest
import org.stellar.anchor.api.rpc.method.NotifyTransactionExpiredRequest
import org.stellar.anchor.api.rpc.method.NotifyTransactionOnHoldRequest
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.event.EventService.Session
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep6Transaction
import org.stellar.anchor.platform.data.JdbcSepTransaction
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrustRepo
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore

/**
 * What a held transaction that never received funds can do next (spec HOLD-03, HOLD-04, HOLD-06):
 * the real handlers run one after another on the same transaction object, as the store would hand
 * it back, so a hold that fabricated `transferReceivedAt` shows up in the next call.
 */
class OnHoldFundsGateTest {
  companion object {
    private const val TX_ID = "testId"
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
  @MockK(relaxed = true) private lateinit var pendingTrustRepo: JdbcTransactionPendingTrustRepo

  private lateinit var onHold: NotifyTransactionOnHoldHandler
  private lateinit var offchainAvailable: NotifyOffchainFundsAvailableHandler
  private lateinit var offchainSent: NotifyOffchainFundsSentHandler
  private lateinit var offchainReceived: NotifyOffchainFundsReceivedHandler
  private lateinit var expired: NotifyTransactionExpiredHandler

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    every { eventService.createSession(any(), TRANSACTION) } returns eventSession
    every { metricsService.counter(any(), any(), any()) } returns sepTransactionCounter
    onHold =
      NotifyTransactionOnHoldHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService
      )
    offchainAvailable =
      NotifyOffchainFundsAvailableHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService
      )
    offchainSent =
      NotifyOffchainFundsSentHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService
      )
    offchainReceived =
      NotifyOffchainFundsReceivedHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService
      )
    expired =
      NotifyTransactionExpiredHandler(
        txn6Store,
        txn24Store,
        txn31Store,
        requestValidator,
        assetService,
        eventService,
        metricsService,
        pendingTrustRepo
      )
  }

  /** A transaction in `pending_user_transfer_start` that has not received any funds. */
  private fun unpaidTransaction(protocol: String, kind: String): JdbcSepTransaction {
    every { txn31Store.findByTransactionId(any()) } returns null
    if (protocol == "24") {
      val txn24 = JdbcSep24Transaction()
      txn24.status = PENDING_USR_TRANSFER_START.toString()
      txn24.kind = kind
      every { txn6Store.findByTransactionId(any()) } returns null
      every { txn24Store.findByTransactionId(TX_ID) } returns txn24
      return txn24
    }
    val txn6 = JdbcSep6Transaction()
    txn6.status = PENDING_USR_TRANSFER_START.toString()
    txn6.kind = kind
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn6Store.findByTransactionId(TX_ID) } returns txn6
    return txn6
  }

  private fun hold(txn: JdbcSepTransaction) {
    onHold.handle(NotifyTransactionOnHoldRequest.builder().transactionId(TX_ID).build())
    assertEquals(ON_HOLD.toString(), txn.status)
  }

  @CsvSource(value = ["24,withdrawal", "6,withdrawal", "6,withdrawal-exchange"])
  @ParameterizedTest
  fun held_unpaid_withdrawal_refuses_offchain_funds_available_and_sent(
    protocol: String,
    kind: String
  ) {
    val txn = unpaidTransaction(protocol, kind)
    hold(txn)

    val availableEx =
      assertThrows<InvalidRequestException> {
        offchainAvailable.handle(
          NotifyOffchainFundsAvailableRequest.builder().transactionId(TX_ID).build()
        )
      }
    val sentEx =
      assertThrows<InvalidRequestException> {
        offchainSent.handle(NotifyOffchainFundsSentRequest.builder().transactionId(TX_ID).build())
      }

    assertTrue(availableEx.message!!.contains("funds received[false]"), availableEx.message)
    assertTrue(sentEx.message!!.contains("funds received[false]"), sentEx.message)
    assertEquals(ON_HOLD.toString(), txn.status)
    assertNull(txn.transferReceivedAt)
  }

  @CsvSource(value = ["24,withdrawal", "6,withdrawal", "6,withdrawal-exchange"])
  @ParameterizedTest
  fun held_unpaid_withdrawal_can_be_expired(protocol: String, kind: String) {
    val txn = unpaidTransaction(protocol, kind)
    hold(txn)

    expired.handle(
      NotifyTransactionExpiredRequest.builder().transactionId(TX_ID).message("expired").build()
    )

    // The mocked store hands back the same object, so the status above is what the second save
    // (after the hold's own save) persisted.
    assertEquals(EXPIRED.toString(), txn.status)
    if (protocol == "24") {
      verify(exactly = 2) { txn24Store.save(txn as JdbcSep24Transaction) }
    } else {
      verify(exactly = 2) { txn6Store.save(txn as JdbcSep6Transaction) }
    }
  }

  @CsvSource(value = ["24,deposit", "6,deposit", "6,deposit-exchange"])
  @ParameterizedTest
  fun held_deposit_resumes_with_offchain_funds_received(protocol: String, kind: String) {
    val txn = unpaidTransaction(protocol, kind)
    hold(txn)
    assertNull(txn.transferReceivedAt)

    offchainReceived.handle(
      NotifyOffchainFundsReceivedRequest.builder().transactionId(TX_ID).build()
    )

    assertEquals(PENDING_ANCHOR.toString(), txn.status)
    assertNotNull(txn.transferReceivedAt)
  }
}
