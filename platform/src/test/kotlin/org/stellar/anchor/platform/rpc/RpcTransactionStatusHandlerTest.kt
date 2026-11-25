package org.stellar.anchor.platform.rpc

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.exception.rpc.InvalidRequestException
import org.stellar.anchor.api.rpc.method.NotifyInteractiveFlowCompletedRequest
import org.stellar.anchor.api.rpc.method.NotifyOffchainFundsReceivedRequest
import org.stellar.anchor.api.rpc.method.RequestOffchainFundsRequest
import org.stellar.anchor.api.rpc.method.RpcMethod
import org.stellar.anchor.api.rpc.method.RpcMethod.NOTIFY_INTERACTIVE_FLOW_COMPLETED
import org.stellar.anchor.api.rpc.method.RpcMethodParamsRequest
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.anchor.api.shared.InstructionField
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.event.EventService
import org.stellar.anchor.metrics.MetricsService
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSepTransaction
import org.stellar.anchor.platform.utils.CapturingLogger
import org.stellar.anchor.platform.validator.RequestValidator
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.util.Log

@ExtendWith(LockAndMockTest::class)
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

    override fun updateTransactionWithRpcRequest(
      txn: JdbcSepTransaction?,
      request: NotifyInteractiveFlowCompletedRequest?
    ) {}

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

  private lateinit var handler: RpcTransactionStatusHandler<NotifyInteractiveFlowCompletedRequest>

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

  // A handler over any request type, so the log tests can use requests that carry customer data
  class LoggingTestHandler<T : RpcMethodParamsRequest>(
    txn6Store: Sep6TransactionStore,
    txn24Store: Sep24TransactionStore,
    txn31Store: Sep31TransactionStore,
    requestValidator: RequestValidator,
    assetService: AssetService,
    eventService: EventService,
    metricsService: MetricsService,
    requestType: Class<T>,
  ) :
    RpcTransactionStatusHandler<T>(
      txn6Store,
      txn24Store,
      txn31Store,
      requestValidator,
      assetService,
      eventService,
      metricsService,
      requestType
    ) {
    override fun getRpcMethod(): RpcMethod = NOTIFY_INTERACTIVE_FLOW_COMPLETED

    override fun getSupportedStatuses(txn: JdbcSepTransaction?): Set<SepTransactionStatus> =
      setOf(INCOMPLETE, ERROR)

    override fun updateTransactionWithRpcRequest(txn: JdbcSepTransaction?, request: T?) {}

    override fun getNextStatus(txn: JdbcSepTransaction?, request: T?): SepTransactionStatus =
      PENDING_ANCHOR
  }

  private fun <T : RpcMethodParamsRequest> loggingHandler(requestType: Class<T>) =
    LoggingTestHandler(
      txn6Store,
      txn24Store,
      txn31Store,
      requestValidator,
      assetService,
      eventService,
      metricsService,
      requestType
    )

  private fun stubOnly24(txn: JdbcSep24Transaction) {
    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns txn
    every { txn31Store.findByTransactionId(any()) } returns null
  }

  private fun sep24TransactionWithCustomerData() =
    JdbcSep24Transaction().apply {
      id = TX_ID
      kind = "deposit"
      status = INCOMPLETE.toString()
      memo = "sentinel-memo-7731"
      memoType = "text"
      requestClientIpAddress = "203.0.113.77"
    }

  // TXLOG-06, TXLOG-07: the INFO line names the method and the transaction, nothing else
  @Test
  @LockAndMockStatic([Log::class])
  fun `handle logs the RPC method and transaction id but no request member`() {
    val logger = CapturingLogger()
    every { Log.getLogger() } returns logger
    stubOnly24(sep24TransactionWithCustomerData())
    val request =
      RequestOffchainFundsRequest.builder()
        .transactionId(TX_ID)
        .message("sentinel-message-4410")
        .instructions(
          mapOf("iban" to InstructionField("DE89370400440532013000", "sentinel-description-9921"))
        )
        .build()

    loggingHandler(RequestOffchainFundsRequest::class.java).handle(request)

    assertTrue(logger.infoLines.isNotEmpty())
    val line = logger.infoLines.single { it.contains("Processing RPC request") }
    assertTrue(line.contains(NOTIFY_INTERACTIVE_FLOW_COMPLETED.toString()), line)
    assertTrue(line.contains(TX_ID), line)
    val everything = logger.allLines.joinToString("\n")
    listOf("sentinel-message-4410", "DE89370400440532013000", "sentinel-description-9921").forEach {
      assertFalse(everything.contains(it), "log leaked $it:\n$everything")
    }
  }

  @Test
  @LockAndMockStatic([Log::class])
  fun `handle logs no external transaction id from the request`() {
    val logger = CapturingLogger()
    every { Log.getLogger() } returns logger
    stubOnly24(sep24TransactionWithCustomerData())
    val request =
      NotifyOffchainFundsReceivedRequest.builder()
        .transactionId(TX_ID)
        .externalTransactionId("sentinel-external-id-5582")
        .build()

    loggingHandler(NotifyOffchainFundsReceivedRequest::class.java).handle(request)

    assertTrue(logger.infoLines.isNotEmpty())
    assertFalse(logger.allLines.joinToString("\n").contains("sentinel-external-id-5582"))
  }

  @Test
  @LockAndMockStatic([Log::class])
  fun `handle logs without throwing when the request has no transaction id`() {
    val logger = CapturingLogger()
    every { Log.getLogger() } returns logger
    every { txn6Store.findByTransactionId(any()) } returns null
    every { txn24Store.findByTransactionId(any()) } returns null
    every { txn31Store.findByTransactionId(any()) } returns null

    assertThrows<InvalidRequestException> {
      loggingHandler(RequestOffchainFundsRequest::class.java)
        .handle(RequestOffchainFundsRequest.builder().build())
    }

    assertTrue(logger.infoLines.any { it.contains("Processing RPC request") })
  }
}
