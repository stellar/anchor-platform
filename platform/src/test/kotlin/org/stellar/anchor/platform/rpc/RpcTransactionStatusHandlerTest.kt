package org.stellar.anchor.platform.rpc

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.rpc.method.NotifyInteractiveFlowCompletedRequest
import org.stellar.anchor.api.rpc.method.RpcMethod
import org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_INTERACTIVE_FLOW_COMPLETED
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSepTransaction
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore

class RpcTransactionStatusHandlerTest {

  // test implementation
  class RpcTransactionStatusHandlerTestImpl(
    txn6Store: Sep6TransactionStore,
    txn24Store: Sep24TransactionStore,
    txn31Store: Sep31TransactionStore,
    requestValidator: RequestValidator,
    assetService: AssetService,
    eventService: EventService,
    metricsService: MetricsService
  ) :
    RpcTransactionStatusHandler<NotifyInteractiveFlowCompletedRequest>(
      txn6Store,
      txn24Store,
      txn31Store,
      requestValidator,
      assetService,
      eventService,
      metricsService,
      NotifyInteractiveFlowCompletedRequest::class.java
    ) {
    override fun getRpcMethod(): RpcMethod {
      return NOTIFY_INTERACTIVE_FLOW_COMPLETED
    }

    override fun getSupportedStatuses(txn: JdbcSepTransaction?): Set<SepTransactionStatus> {
      return setOf(INCOMPLETE, ERROR)
    }

    var rejectInUpdate = false

    override fun updateTransactionWithRpcRequest(
      txn: JdbcSepTransaction?,
      request: NotifyInteractiveFlowCompletedRequest?
    ) {
      if (rejectInUpdate) throw InvalidParamsException("rejected by the method")
    }

    override fun getNextStatus(
      txn: JdbcSepTransaction?,
      request: NotifyInteractiveFlowCompletedRequest?
    ): SepTransactionStatus {
      return PENDING_ANCHOR
    }
  }

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

  private lateinit var handler: RpcTransactionStatusHandlerTestImpl

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    this.handler =
      RpcTransactionStatusHandlerTestImpl(
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
  fun test_handle_transactionIsNotFound() {
    val request = NotifyInteractiveFlowCompletedRequest.builder().transactionId(TX_ID).build()
    val tnx24 = JdbcSep24Transaction()
    tnx24.status = INCOMPLETE.toString()

    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    val ex = assertThrows<InvalidRequestException> { handler.handle(request) }
    assertEquals("Transaction with id[testId] is not found", ex.message)

    verify(exactly = 0) { txn6Store.save(any()) }
    verify(exactly = 0) { txn24Store.save(any()) }
    verify(exactly = 0) { txn31Store.save(any()) }
  }

  @Test
  fun test_isErrorStatus() {
    setOf(ERROR, EXPIRED).forEach { s -> assertTrue(handler.isErrorStatus(s)) }

    Arrays.stream(SepTransactionStatus.values())
      .filter { s -> !setOf(ERROR, EXPIRED).contains(s) }
      .forEach { s -> assertFalse(handler.isErrorStatus(s)) }
  }

  @Test
  fun test_isFinalStatus() {
    setOf(REFUNDED, COMPLETED).forEach { s -> assertTrue(handler.isFinalStatus(s)) }

    Arrays.stream(SepTransactionStatus.values())
      .filter { s -> !setOf(REFUNDED, COMPLETED).contains(s) }
      .forEach { s -> assertFalse(handler.isFinalStatus(s)) }
  }

  private fun txnWithUserActionRequiredBy(value: Instant?) =
    JdbcSep24Transaction().apply {
      status = INCOMPLETE.toString()
      userActionRequiredBy = value
    }

  private fun requestWithUserActionRequiredBy(value: Instant?) =
    NotifyInteractiveFlowCompletedRequest.builder()
      .transactionId(TX_ID)
      .userActionRequiredBy(value)
      .build()

  // ANCHOR-1587 (HackerOne #4241206): a request the method rejects must not leave its
  // user_action_required_by on the transaction object.
  @Test
  fun `updateTransaction leaves user_action_required_by unchanged when the method update throws`() {
    val before = Instant.parse("2031-01-01T00:00:00Z")
    val requested = Instant.parse("2032-02-02T00:00:00Z")
    handler.rejectInUpdate = true

    listOf(null, before).forEach { prior ->
      val txn = txnWithUserActionRequiredBy(prior)
      assertThrows<InvalidParamsException> {
        handler.updateTransaction(txn, requestWithUserActionRequiredBy(requested))
      }
      assertEquals(prior, txn.userActionRequiredBy)
    }
    verify(exactly = 0) { txn24Store.save(any()) }
  }

  @Test
  fun `updateTransaction stores the request user_action_required_by when the update succeeds`() {
    val requested = Instant.now().plusSeconds(3600)
    val txn = txnWithUserActionRequiredBy(Instant.parse("2031-01-01T00:00:00Z"))

    handler.updateTransaction(txn, requestWithUserActionRequiredBy(requested))

    assertEquals(requested, txn.userActionRequiredBy)
  }

  @Test
  fun `updateTransaction clears user_action_required_by when the request has none`() {
    val txn = txnWithUserActionRequiredBy(Instant.parse("2031-01-01T00:00:00Z"))

    handler.updateTransaction(txn, requestWithUserActionRequiredBy(null))

    assertEquals(null, txn.userActionRequiredBy)
  }
}
