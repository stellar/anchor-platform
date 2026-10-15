package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.RejectedExecutionException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.api.platform.HealthCheckStatus
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.ledger.LedgerClientHelper
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPathPaymentOperation
import org.stellar.anchor.ledger.PaymentTransferEvent
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.anchor.platform.observer.stellar.AbstractPaymentObserver.ObserverStatus
import org.stellar.sdk.Address
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.TOID
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.exception.SorobanRpcException
import org.stellar.sdk.requests.sorobanrpc.EventFilterType
import org.stellar.sdk.requests.sorobanrpc.GetEventsRequest
import org.stellar.sdk.responses.sorobanrpc.GetEventsResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.SCMap
import org.stellar.sdk.xdr.SCMapEntry
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType

class StellarRpcPaymentObserverTest {
  private lateinit var config: StellarPaymentObserverConfig
  private lateinit var paymentListeners: List<PaymentListener>
  private lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager
  private lateinit var paymentStreamerCursorStore: StellarPaymentStreamerCursorStore
  private lateinit var sacToAssetMapper: MockSacToAssetMapper
  private lateinit var observer: StellarRpcPaymentObserver
  private lateinit var assetService: AssetService
  private lateinit var stellarRpc: StellarRpc
  private lateinit var sorobanServer: SorobanServer

  @BeforeEach
  fun setUp() {
    config =
      StellarPaymentObserverConfig().apply {
        silenceCheckInterval = 60
        silenceTimeout = 300
        silenceTimeoutRetries = 3
        initialStreamBackoffTime = 1000
        maxStreamBackoffTime = 10000
        initialEventBackoffTime = 500
        maxEventBackoffTime = 5000
      }
    stellarRpc = mockk(relaxed = true)
    sorobanServer = mockk(relaxed = true)
    every { stellarRpc.sorobanServer } returns sorobanServer
    every { stellarRpc.getSorobanServer() } returns sorobanServer
    every { sorobanServer.getLatestLedger() } returns mockk { every { sequence } returns 10 }

    paymentListeners = emptyList()
    paymentObservingAccountsManager = mockk(relaxed = true)
    paymentStreamerCursorStore = mockk(relaxed = true)
    sacToAssetMapper = MockSacToAssetMapper()
    assetService = mockk(relaxed = true)
    observer =
      spyk(
        StellarRpcPaymentObserver(
          stellarRpc,
          config,
          paymentListeners,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
          sacToAssetMapper,
          assetService,
        ),
        recordPrivateCalls = true,
      )
    observer.setStatus(ObserverStatus.RUNNING)
  }

  private fun verified(
    contractId: String,
    amount: BigInteger,
    from: String = KeyPair.random().accountId,
    to: String = KeyPair.random().accountId,
  ): StellarRpcPaymentObserver.ShouldProcessResult {
    val event = mockk<GetEventsResponse.EventInfo>()
    every { event.contractId } returns contractId
    return StellarRpcPaymentObserver.ShouldProcessResult.builder()
      .event(event)
      .shouldProcess(true)
      .fromAddr(from)
      .toAddr(to)
      .amount(amount)
      .build()
  }

  private fun eventWithId(id: String): GetEventsResponse.EventInfo {
    val event = mockk<GetEventsResponse.EventInfo>(relaxed = true)
    every { event.id } returns id
    return event
  }

  private fun processAll() {
    every { observer["shouldProcess"](any<GetEventsResponse.EventInfo>()) } answers
      {
        StellarRpcPaymentObserver.ShouldProcessResult.builder()
          .event(firstArg())
          .shouldProcess(true)
          .build()
      }
  }

  @Test
  fun `processEvents stops at a transient failure and resumes after the last processed event`() {
    val e1 = eventWithId("e1")
    val e2 = eventWithId("e2")
    val e3 = eventWithId("e3")
    processAll()
    every { observer.processTransferEvent(any()) } answers
      {
        if (firstArg<StellarRpcPaymentObserver.ShouldProcessResult>().event === e2)
          throw IOException("platform unavailable")
      }

    assertEquals("e1", observer.processEvents(listOf(e1, e2, e3), "END"))
    verify(exactly = 0) { observer.processTransferEvent(match { it.event === e3 }) }
  }

  @Test
  fun `fetchEvents waits before retrying after an event was held`() {
    val e1 = eventWithId("e1")
    processAll()
    every { observer.processTransferEvent(any()) } throws IOException("database failed")

    assertNull(observer.processEvents(listOf(e1), "END"))
    observer.fetchEvents()

    verify(exactly = 0) { sorobanServer.getEvents(any()) }
  }

  @Test
  fun `the wait after a held event doubles from 1 second to a 30 second cap`() {
    val e1 = eventWithId("e1")
    processAll()
    every { observer.processTransferEvent(any()) } throws IOException("database failed")

    val waits = mutableListOf<Long>()
    repeat(6) {
      observer.processEvents(listOf(e1), "END")
      waits.add(observer.holdBackoffSeconds)
    }

    assertEquals(listOf(2L, 4L, 8L, 16L, 30L, 30L), waits)
  }

  @Test
  fun `the first held event delays the next fetch by 1 second`() {
    val e1 = eventWithId("e1")
    processAll()
    every { observer.processTransferEvent(any()) } throws IOException("database failed")

    val before = java.time.Instant.now()
    observer.processEvents(listOf(e1), "END")
    val after = java.time.Instant.now()

    assertFalse(observer.retryNotBefore.isBefore(before.plusSeconds(1)))
    assertFalse(observer.retryNotBefore.isAfter(after.plusSeconds(1)))
  }

  @Test
  fun `a network error on an event also delays the next fetch`() {
    val e1 = eventWithId("e1")
    processAll()
    every { observer.processTransferEvent(any()) } throws NetworkException(503, "unavailable")

    observer.processEvents(listOf(e1), "END")

    assertEquals(2L, observer.holdBackoffSeconds)
    assertTrue(observer.retryNotBefore.isAfter(java.time.Instant.now()))
  }

  @Test
  fun `a clean batch resets the wait to 1 second`() {
    val e1 = eventWithId("e1")
    processAll()
    every { observer.processTransferEvent(any()) } throws IOException("database failed")
    repeat(3) { observer.processEvents(listOf(e1), "END") }

    every { observer.processTransferEvent(any()) } returns Unit
    assertEquals("END", observer.processEvents(listOf(e1), "END"))

    assertEquals(1L, observer.holdBackoffSeconds)
    assertFalse(observer.retryNotBefore.isAfter(java.time.Instant.now()))
  }

  @Test
  fun `an empty batch resets the wait to 1 second`() {
    val e1 = eventWithId("e1")
    processAll()
    every { observer.processTransferEvent(any()) } throws IOException("database failed")
    repeat(3) { observer.processEvents(listOf(e1), "END") }

    assertEquals("END", observer.processEvents(emptyList(), "END"))

    assertEquals(1L, observer.holdBackoffSeconds)
    assertFalse(observer.retryNotBefore.isAfter(java.time.Instant.now()))
  }

  @Test
  fun `waiting out a held event is not counted as silence`() {
    val e1 = eventWithId("e1")
    processAll()
    every { observer.processTransferEvent(any()) } throws IOException("database failed")
    observer.processEvents(listOf(e1), "END")
    val stale = java.time.Instant.now().minusSeconds(3600)
    observer.lastActivityTime = stale

    observer.fetchEvents()

    assertTrue(observer.lastActivityTime.isAfter(stale))
  }

  @Test
  fun `processEvents keeps the current cursor when the first event fails transiently`() {
    val e1 = eventWithId("e1")
    processAll()
    every { observer.processTransferEvent(any()) } throws NetworkException(503, "unavailable")

    assertNull(observer.processEvents(listOf(e1), "END"))
  }

  @Test
  fun `processEvents skips an event that fails with a non-transient error and moves on`() {
    val e1 = eventWithId("e1")
    val e2 = eventWithId("e2")
    processAll()
    every { observer.processTransferEvent(any()) } answers
      {
        if (firstArg<StellarRpcPaymentObserver.ShouldProcessResult>().event === e1)
          throw IllegalStateException("bad data")
      }

    assertEquals("END", observer.processEvents(listOf(e1, e2), "END"))
    verify(exactly = 1) { observer.processTransferEvent(match { it.event === e2 }) }
  }

  @Test
  fun `processTransferEvent skips a transaction that cannot be decoded instead of retrying`() {
    val event = eventWithId("e1")
    every { event.transactionHash } returns "poisonTx"
    every { event.operationIndex } returns 0L
    every { stellarRpc.getTransaction("poisonTx") } throws
      org.stellar.anchor.api.exception.LedgerDecodeException(
        "Unable to parse transaction envelope",
        IllegalArgumentException("Unknown enum value: 22"),
      )

    assertDoesNotThrow {
      observer.processTransferEvent(
        StellarRpcPaymentObserver.ShouldProcessResult.builder()
          .event(event)
          .shouldProcess(true)
          .build()
      )
    }
    verify(exactly = 0) { observer.processOperation(any(), any(), any()) }
  }

  @Test
  fun `processEvents moves past an undecodable transaction and processes the next event`() {
    val poison = eventWithId("e1")
    every { poison.transactionHash } returns "poisonTx"
    every { poison.operationIndex } returns 0L
    val next = eventWithId("e2")
    processAll()
    every { stellarRpc.getTransaction("poisonTx") } throws
      org.stellar.anchor.api.exception.LedgerDecodeException(
        "Unable to parse transaction envelope",
        IllegalArgumentException("Unknown enum value: 22"),
      )
    every { observer.processTransferEvent(match { it.event === next }) } answers {}

    assertEquals("END", observer.processEvents(listOf(poison, next), "END"))
    verify(exactly = 1) { observer.processTransferEvent(match { it.event === next }) }
  }

  @Test
  fun `processTransferEvent credits a contract call the ledger parser did not model`() {
    val event = eventWithId("e1")
    every { event.transactionHash } returns "txHashRouter"
    every { event.operationIndex } returns 0L
    val txn =
      LedgerTransaction.builder()
        .hash("txHashRouter")
        .sequenceNumber(100L)
        .applicationOrder(1)
        .operations(listOf())
        .build()
    every { stellarRpc.getTransaction("txHashRouter") } returns txn
    mockkStatic(LedgerClientHelper::class)
    try {
      every { LedgerClientHelper.isInvokeHostFunctionOperation(any(), 0) } returns true
      val opSlot = slot<LedgerOperation>()
      every { observer.processOperation(any(), capture(opSlot), any()) } answers {}

      observer.processTransferEvent(
        StellarRpcPaymentObserver.ShouldProcessResult.builder()
          .event(event)
          .shouldProcess(true)
          .build()
      )

      assertEquals(OperationType.INVOKE_HOST_FUNCTION, opSlot.captured.type)
      assertEquals(
        TOID(100, 1, 1).toInt64().toString(),
        opSlot.captured.invokeHostFunctionOperation.id,
      )
    } finally {
      unmockkStatic(LedgerClientHelper::class)
    }
  }

  @Test
  fun `processOperation takes from and to from the verified event, not the top-level declared arguments`() {
    val ledgerTxn = mockk<LedgerTransaction>()
    val cId = "verifiedSacContractId"
    sacToAssetMapper.resolvableContracts = setOf(cId)
    val verifiedFrom = KeyPair.random().accountId
    val verifiedTo = KeyPair.random().accountId
    val invokeOp =
      LedgerTransaction.LedgerInvokeHostFunctionOperation().apply {
        from = KeyPair.random().accountId
        to = KeyPair.random().accountId
        id = "opIdDeclared"
        contractId = "routerContractId"
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.INVOKE_HOST_FUNCTION
        invokeHostFunctionOperation = invokeOp
      }
    every { ledgerTxn.hash } returns "txHashDeclared"
    val eventSlot = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(eventSlot)) } answers {}

    observer.processOperation(
      ledgerTxn,
      op,
      verified(cId, BigInteger.TEN, verifiedFrom, verifiedTo),
    )

    assertEquals(verifiedFrom, eventSlot.captured.from)
    assertEquals(verifiedTo, eventSlot.captured.to)
  }

  @Test
  fun `processOperation creates PaymentTransferEvent for PAYMENT operation`() {
    // Arrange
    val ledgerTxn = mockk<LedgerTransaction>()
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val paymentOp =
      LedgerTransaction.LedgerPaymentOperation().apply {
        from = fromAccount
        to = toAccount
        asset = Asset.createNativeAsset().toXdr()
        amount = BigInteger("100")
        id = "opId"
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.PAYMENT
        paymentOperation = paymentOp
      }

    every { ledgerTxn.hash } returns "txHash"

    val eventSlot = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(eventSlot)) } answers {}

    // Act
    observer.processOperation(ledgerTxn, op, verified("ignoredContractId", BigInteger.ZERO))

    // Assert
    val event = eventSlot.captured
    assertEquals(fromAccount, event.from)
    assertEquals(toAccount, event.to)
    assertEquals(BigInteger.valueOf(100), event.amount)
    assertEquals("txHash", event.txHash)
    assertEquals("opId", event.operationId)
    assertEquals(ledgerTxn, event.ledgerTransaction)
  }

  @Test
  fun `processOperation creates PaymentTransferEvent for PATH_PAYMENT_STRICT_SEND operation`() {
    // Arrange
    val ledgerTxn = mockk<LedgerTransaction>()
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val assetXdr = Asset.createNativeAsset().toXdr()
    val pathPaymentOp =
      LedgerPathPaymentOperation().apply {
        from = fromAccount
        to = toAccount
        asset = assetXdr
        amount = BigInteger("200")
        id = "pathSendOpId"
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.PATH_PAYMENT_STRICT_SEND
        pathPaymentOperation = pathPaymentOp
      }

    every { ledgerTxn.hash } returns "txHashSend"
    // Optionally mock AssetHelper.getSep11AssetName if needed

    val eventSlot = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(eventSlot)) } answers {}

    // Act
    observer.processOperation(ledgerTxn, op, verified("ignoredContractId", BigInteger.ZERO))

    // Assert
    val event = eventSlot.captured
    assertEquals(fromAccount, event.from)
    assertEquals(toAccount, event.to)
    assertEquals(BigInteger.valueOf(200), event.amount)
    assertEquals("txHashSend", event.txHash)
    assertEquals("pathSendOpId", event.operationId)
    assertEquals(ledgerTxn, event.ledgerTransaction)
  }

  @Test
  fun `processOperation creates PaymentTransferEvent for PATH_PAYMENT_STRICT_RECEIVE operation`() {
    // Arrange
    val ledgerTxn = mockk<LedgerTransaction>()
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val assetXdr = Asset.createNativeAsset().toXdr()
    val pathPaymentOp =
      LedgerPathPaymentOperation().apply {
        from = fromAccount
        to = toAccount
        asset = assetXdr
        amount = BigInteger("300")
        id = "pathReceiveOpId"
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.PATH_PAYMENT_STRICT_RECEIVE
        pathPaymentOperation = pathPaymentOp
      }

    every { ledgerTxn.hash } returns "txHashReceive"
    // Optionally mock AssetHelper.getSep11AssetName if needed

    val eventSlot = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(eventSlot)) } answers {}

    // Act
    observer.processOperation(ledgerTxn, op, verified("ignoredContractId", BigInteger.ZERO))

    // Assert
    val event = eventSlot.captured
    assertEquals(fromAccount, event.from)
    assertEquals(toAccount, event.to)
    assertEquals(BigInteger.valueOf(300), event.amount)
    assertEquals("txHashReceive", event.txHash)
    assertEquals("pathReceiveOpId", event.operationId)
    assertEquals(ledgerTxn, event.ledgerTransaction)
  }

  @Test
  fun `processOperation creates PaymentTransferEvent for INVOKE_HOST_FUNCTION operation`() {
    // Arrange
    val ledgerTxn = mockk<LedgerTransaction>()
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val cId = "contractId123"
    val opId = "opIdInvoke"

    val invokeOp =
      LedgerTransaction.LedgerInvokeHostFunctionOperation().apply {
        from = fromAccount
        to = toAccount
        asset = Asset.createNativeAsset().toXdr()
        amount = BigInteger("400")
        id = opId
        contractId = cId
      }

    val op =
      LedgerOperation().apply {
        type = OperationType.INVOKE_HOST_FUNCTION
        invokeHostFunctionOperation = invokeOp
      }

    every { ledgerTxn.hash } returns "txHashInvoke"

    val eventSlot = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(eventSlot)) } answers {}

    // Act
    observer.processOperation(
      ledgerTxn,
      op,
      verified(cId, BigInteger.valueOf(400L), fromAccount, toAccount),
    )

    // Assert
    val event = eventSlot.captured
    assertEquals(fromAccount, event.from)
    assertEquals(toAccount, event.to)
    assertEquals(BigInteger.valueOf(400), event.amount)
    assertEquals("txHashInvoke", event.txHash)
    assertEquals(opId, event.operationId)
    assertEquals(ledgerTxn, event.ledgerTransaction)
  }

  @Test
  fun `processOperation resolves the asset from the event-emitting contract, not the top-level invoked contract`() {
    val ledgerTxn = mockk<LedgerTransaction>()
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val routerContractId = "routerContractId"
    val sacContractId = "realSacContractId"
    val opId = "opIdRouter"

    sacToAssetMapper.resolvableContracts = setOf(sacContractId)

    val invokeOp =
      LedgerTransaction.LedgerInvokeHostFunctionOperation().apply {
        from = fromAccount
        to = toAccount
        amount = BigInteger("500")
        id = opId
        contractId = routerContractId
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.INVOKE_HOST_FUNCTION
        invokeHostFunctionOperation = invokeOp
      }

    every { ledgerTxn.hash } returns "txHashRouter"

    val eventSlot = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(eventSlot)) } answers {}

    observer.processOperation(
      ledgerTxn,
      op,
      verified(sacContractId, BigInteger.valueOf(500L), fromAccount, toAccount),
    )

    val event = eventSlot.captured
    assertEquals(fromAccount, event.from)
    assertEquals(toAccount, event.to)
    assertEquals(BigInteger.valueOf(500), event.amount)
  }

  @Test
  fun `processOperation takes the amount from the verified event, not the top-level declared amount`() {
    val ledgerTxn = mockk<LedgerTransaction>()
    val cId = "someSacContractId"
    sacToAssetMapper.resolvableContracts = setOf(cId)

    val invokeOp =
      LedgerTransaction.LedgerInvokeHostFunctionOperation().apply {
        from = KeyPair.random().accountId
        to = KeyPair.random().accountId
        amount = BigInteger("999999999999")
        id = "opIdForgedAmount"
        contractId = "attackerRouterContractId"
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.INVOKE_HOST_FUNCTION
        invokeHostFunctionOperation = invokeOp
      }

    every { ledgerTxn.hash } returns "txHashForgedAmount"

    val eventSlot = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(eventSlot)) } answers {}

    observer.processOperation(ledgerTxn, op, verified(cId, BigInteger.ONE))

    val event = eventSlot.captured
    assertEquals(BigInteger.ONE, event.amount)
    assertEquals(BigInteger("999999999999"), invokeOp.amount)
  }

  @Test
  fun `processOperation skips the operation instead of throwing when the event-emitting contract is not a genuine SAC`() {
    val ledgerTxn = mockk<LedgerTransaction>()
    val forwarderContractId = "nonSacForwarderContractId"

    sacToAssetMapper.resolvableContracts = setOf("someOtherRealSac")

    val invokeOp =
      LedgerTransaction.LedgerInvokeHostFunctionOperation().apply {
        from = KeyPair.random().accountId
        to = KeyPair.random().accountId
        amount = BigInteger("999")
        id = "opIdForwarder"
        contractId = forwarderContractId
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.INVOKE_HOST_FUNCTION
        invokeHostFunctionOperation = invokeOp
      }

    every { ledgerTxn.hash } returns "txHashForwarder"

    assertDoesNotThrow {
      observer.processOperation(
        ledgerTxn,
        op,
        verified(forwarderContractId, BigInteger.valueOf(999L)),
      )
    }

    verify(exactly = 0) { observer["handleEvent"](any<PaymentTransferEvent>()) }
  }

  @Test
  fun `startInternal recreates the executor after shutdown instead of throwing RejectedExecutionException`() {
    observer.startInternal()
    assertFalse(observer.executorService.isShutdown)

    observer.shutdownInternal()
    assertTrue(observer.executorService.isShutdown)

    assertDoesNotThrow { observer.startInternal() }

    assertFalse(observer.executorService.isShutdown)
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())

    observer.shutdownInternal()
  }

  @Test
  fun `restartInternal resumes polling after the executor was shut down`() {
    val response = mockk<GetEventsResponse>()
    every { observer.buildEventRequests(any()) } returns listOf(mockk())
    every { response.events } returns emptyList()
    every { response.latestLedger } returns 1L
    every { response.cursor } returns "CUR"
    every { sorobanServer.getEvents(any()) } returns response
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }

    observer.startInternal()
    observer.shutdownInternal()
    assertTrue(observer.executorService.isShutdown)

    assertDoesNotThrow { observer.restartInternal() }

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertFalse(observer.executorService.isShutdown)
    verify(timeout = 2000, atLeast = 1) { sorobanServer.getEvents(any()) }

    observer.shutdownInternal()
  }

  @Test
  fun `restartInternal swallows a failed restart instead of propagating it to the supervisor`() {
    observer.setStatus(ObserverStatus.RUNNING)
    justRun { observer.shutdownInternal() }
    every { observer.startInternal() } throws RejectedExecutionException("executor terminated")

    assertDoesNotThrow { observer.restartInternal() }

    verify { observer.startInternal() }
  }

  @Test
  fun `checkStatus never lets an unexpected exception kill the supervisor task`() {
    observer.setStatus(ObserverStatus.NEEDS_SHUTDOWN)
    every { observer.shutdownInternal() } throws IllegalStateException("unexpected bug")

    assertDoesNotThrow { observer.checkStatus() }

    assertEquals(ObserverStatus.NEEDS_SHUTDOWN, observer.getStatus())

    assertDoesNotThrow { observer.checkStatus() }
  }

  @Test
  fun `test buildEventRequests`() {
    val account1 = KeyPair.random().accountId
    val account2 = KeyPair.random().accountId
    // Mock the behavior of getStellarAssets()
    every { assetService.stellarAssets } returns
      listOf(
        StellarAssetInfo().apply { distributionAccount = account1 },
        StellarAssetInfo().apply { distributionAccount = account2 },
      )

    // Call the method under test
    val request = observer.buildEventRequests(null).single()

    // Verify the result
    assertEquals(4, request.filters.size)
    val filterList = request.filters.stream().toList()
    assertEquals(EventFilterType.CONTRACT, filterList[0].type)
    assertEquals(EventFilterType.CONTRACT, filterList[1].type)
    assertEquals(EventFilterType.CONTRACT, filterList[2].type)
    assertEquals(EventFilterType.CONTRACT, filterList[3].type)
    assertEquals(
      Scv.toAddress(account1).toXdrBase64(),
      filterList[0].topics.toList()[0].toList()[1],
    )
    assertEquals(
      Scv.toAddress(account1).toXdrBase64(),
      filterList[1].topics.toList()[0].toList()[2],
    )
    assertEquals(
      Scv.toAddress(account2).toXdrBase64(),
      filterList[2].topics.toList()[0].toList()[1],
    )
    assertEquals(
      Scv.toAddress(account2).toXdrBase64(),
      filterList[3].topics.toList()[0].toList()[2],
    )
  }

  @Test
  fun `fetchEvents resets silence counter and updates metrics on success`() {
    val response = mockk<GetEventsResponse>()
    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { response.events } returns emptyList()
    every { response.latestLedger } returns 123L
    every { response.cursor } returns "CUR123"
    every { sorobanServer.getEvents(any()) } returns response
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }

    observer.silenceTimeoutCount = 2

    observer.fetchEvents()

    assertEquals("CUR123", observer.cursor)
    assertEquals(123L, observer.metricLatestBlockRead.get())
    assertEquals(123L, observer.metricLatestBlockProcessed.get())
    assertEquals(0, observer.silenceTimeoutCount)
    assertNotNull(observer.lastActivityTime)
  }

  @Test
  fun `fetchEvents marks database error when cursor persistence fails`() {
    val response = mockk<GetEventsResponse>()
    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { response.events } returns emptyList()
    every { response.latestLedger } returns 456L
    every { response.cursor } returns "CUR456"
    every { sorobanServer.getEvents(any()) } returns response
    every { paymentStreamerCursorStore.saveStellarRpcCursor(any()) } throws
      RuntimeException("db down")

    observer.metricLatestBlockProcessed.set(7)
    observer.setStatus(ObserverStatus.RUNNING)

    observer.fetchEvents()

    assertEquals(ObserverStatus.DATABASE_ERROR, observer.getStatus())
    assertEquals(7, observer.metricLatestBlockProcessed.get())
  }

  @Test
  fun `fetchEvents keeps running on transient IO errors`() {
    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { sorobanServer.getEvents(any()) } throws IOException("transient rpc error")

    observer.cursor = "CUR0"
    observer.metricLatestBlockRead.set(9)
    observer.metricLatestBlockProcessed.set(8)
    observer.silenceTimeoutCount = 2
    observer.setStatus(ObserverStatus.RUNNING)

    assertDoesNotThrow { observer.fetchEvents() }

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("CUR0", observer.cursor)
    assertEquals(9, observer.metricLatestBlockRead.get())
    assertEquals(8, observer.metricLatestBlockProcessed.get())
    assertEquals(2, observer.silenceTimeoutCount)
  }

  @Test
  fun `fetchEvents keeps running on network errors`() {
    val netEx = mockk<NetworkException>()
    every { netEx.message } returns "net down"
    every { netEx.toString() } returns "net down"
    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { sorobanServer.getEvents(any()) } throws netEx

    observer.cursor = "CURN"
    observer.metricLatestBlockRead.set(11)
    observer.metricLatestBlockProcessed.set(10)
    observer.silenceTimeoutCount = 1
    observer.setStatus(ObserverStatus.RUNNING)

    assertDoesNotThrow { observer.fetchEvents() }

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("CURN", observer.cursor)
    assertEquals(11, observer.metricLatestBlockRead.get())
    assertEquals(10, observer.metricLatestBlockProcessed.get())
    assertEquals(1, observer.silenceTimeoutCount)
  }

  @Test
  fun `fetchEvents handles unexpected errors without stalling`() {
    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { sorobanServer.getEvents(any()) } throws RuntimeException("rpc boom")

    observer.setStatus(ObserverStatus.RUNNING)

    assertDoesNotThrow { observer.fetchEvents() }
    assertEquals(ObserverStatus.STREAM_ERROR, observer.getStatus())
  }

  @Test
  fun `fetchEvents skips empty SCV_MAP poison event without crashing or stalling`() {
    val distAccount = KeyPair.random().accountId
    val attackerAccount = KeyPair.random().accountId

    val topics =
      listOf(
        Scv.toSymbol("transfer").toXdrBase64(),
        Scv.toAddress(distAccount).toXdrBase64(),
        Scv.toAddress(attackerAccount).toXdrBase64(),
        Scv.toString("native").toXdrBase64(),
      )

    val emptyMapValue =
      SCVal.builder()
        .discriminant(SCValType.SCV_MAP)
        .map(SCMap(emptyArray<SCMapEntry>()))
        .build()
        .toXdrBase64()

    val poisonEvent = mockk<GetEventsResponse.EventInfo>()
    every { poisonEvent.topic } returns topics
    every { poisonEvent.value } returns emptyMapValue

    val response = mockk<GetEventsResponse>()
    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { response.events } returns listOf(poisonEvent)
    every { response.latestLedger } returns 777L
    every { response.cursor } returns "SAFE_CUR"
    every { sorobanServer.getEvents(any()) } returns response
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }

    observer.setStatus(ObserverStatus.RUNNING)

    assertDoesNotThrow { observer.fetchEvents() }

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("SAFE_CUR", observer.cursor)
  }

  @Test
  fun `fetchEvents skips one-entry SCV_MAP without crashing or stalling`() {
    val oneEntryMap =
      SCVal.builder()
        .discriminant(SCValType.SCV_MAP)
        .map(
          SCMap(arrayOf(SCMapEntry(Scv.toSymbol("amount"), Scv.toInt128(BigInteger.valueOf(100)))))
        )
        .build()
        .toXdrBase64()

    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { sorobanServer.getEvents(any()) } returns mockPoisonResponse(oneEntryMap)
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.setStatus(ObserverStatus.RUNNING)

    assertDoesNotThrow { observer.fetchEvents() }

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("POISON_CUR", observer.cursor)
  }

  @Test
  fun `fetchEvents skips SCV_MAP with wrong first-entry type without crashing or stalling`() {
    val wrongTypeMap =
      SCVal.builder()
        .discriminant(SCValType.SCV_MAP)
        .map(
          SCMap(
            arrayOf(
              SCMapEntry(Scv.toSymbol("amount"), Scv.toUint64(BigInteger.valueOf(100))),
              SCMapEntry(Scv.toSymbol("memo"), Scv.toString("memo123")),
            )
          )
        )
        .build()
        .toXdrBase64()

    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { sorobanServer.getEvents(any()) } returns mockPoisonResponse(wrongTypeMap)
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.setStatus(ObserverStatus.RUNNING)

    assertDoesNotThrow { observer.fetchEvents() }

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("POISON_CUR", observer.cursor)
  }

  @Test
  fun `fetchEvents handles contract-address recipient with SCV_U64 memo without crashing`() {
    val contractAddress = Address.fromContract(ByteArray(32)).toSCVal()
    val validMapWithU64Memo =
      SCVal.builder()
        .discriminant(SCValType.SCV_MAP)
        .map(
          SCMap(
            arrayOf(
              SCMapEntry(Scv.toSymbol("amount"), Scv.toInt128(BigInteger.valueOf(500))),
              SCMapEntry(Scv.toSymbol("memo"), Scv.toUint64(BigInteger.valueOf(42))),
            )
          )
        )
        .build()
        .toXdrBase64()

    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { sorobanServer.getEvents(any()) } returns
      mockPoisonResponse(validMapWithU64Memo, toSCVal = contractAddress)
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.setStatus(ObserverStatus.RUNNING)

    assertDoesNotThrow { observer.fetchEvents() }

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("POISON_CUR", observer.cursor)
  }

  private fun setupTransferEvent(
    fromAccount: String,
    toAccount: String,
    amount: BigInteger,
    txHash: String,
    operationIndex: Long,
    cursorValue: String = "CURSOR",
  ): GetEventsResponse.EventInfo {
    val topics =
      listOf(
        Scv.toSymbol("transfer").toXdrBase64(),
        Scv.toAddress(fromAccount).toXdrBase64(),
        Scv.toAddress(toAccount).toXdrBase64(),
        Scv.toString("native").toXdrBase64(),
      )
    val event = mockk<GetEventsResponse.EventInfo>(relaxed = true)
    every { event.topic } returns topics
    every { event.value } returns Scv.toInt128(amount).toXdrBase64()
    every { event.transactionHash } returns txHash
    every { event.operationIndex } returns operationIndex

    every { paymentObservingAccountsManager.lookupAndUpdate(toAccount) } returns true
    every { paymentObservingAccountsManager.lookupAndUpdate(fromAccount) } returns false

    val response = mockk<GetEventsResponse>()
    every { observer.buildEventRequests(any()) } returns listOf(mockk<GetEventsRequest>())
    every { sorobanServer.getEvents(any()) } returns response
    every { response.events } returns listOf(event)
    every { response.latestLedger } returns 100L
    every { response.cursor } returns cursorValue
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }

    return event
  }

  @Test
  fun `processTransferEvent credits payment at operationIndex=0 in single-op transaction`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val amount = BigInteger.valueOf(1_000_000L)
    val txHash = "txHashSingleOp"
    val seqNum = 100L
    val appOrder = 1
    val paymentOpId = TOID(seqNum.toInt(), appOrder, 1).toInt64().toString()

    setupTransferEvent(fromAccount, toAccount, amount, txHash, operationIndex = 0L)

    val paymentOp =
      LedgerTransaction.LedgerPaymentOperation().apply {
        from = fromAccount
        to = toAccount
        asset = Asset.createNativeAsset().toXdr()
        this.amount = amount
        id = paymentOpId
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.PAYMENT
        paymentOperation = paymentOp
      }
    val txn =
      LedgerTransaction.builder()
        .hash(txHash)
        .sequenceNumber(seqNum)
        .applicationOrder(appOrder)
        .operations(listOf(op))
        .build()
    every { stellarRpc.getTransaction(txHash) } returns txn

    val capturedEvent = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(capturedEvent)) } answers {}

    observer.fetchEvents()

    assertEquals(txHash, capturedEvent.captured.txHash)
    assertEquals(fromAccount, capturedEvent.captured.from)
    assertEquals(toAccount, capturedEvent.captured.to)
    assertEquals(amount, capturedEvent.captured.amount)
    assertEquals(paymentOpId, capturedEvent.captured.operationId)
  }

  @Test
  fun `processTransferEvent correctly credits payment at operationIndex=1 when a non-payment op precedes it`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val amount = BigInteger.valueOf(2_000_000L)
    val txHash = "txHashMultiOp"
    val seqNum = 200L
    val appOrder = 1
    val paymentOpId = TOID(seqNum.toInt(), appOrder, 2).toInt64().toString()

    setupTransferEvent(fromAccount, toAccount, amount, txHash, operationIndex = 1L, "CURSOR_MULTI")

    val paymentOp =
      LedgerTransaction.LedgerPaymentOperation().apply {
        from = fromAccount
        to = toAccount
        asset = Asset.createNativeAsset().toXdr()
        this.amount = amount
        id = paymentOpId
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.PAYMENT
        paymentOperation = paymentOp
      }
    val txn =
      LedgerTransaction.builder()
        .hash(txHash)
        .sequenceNumber(seqNum)
        .applicationOrder(appOrder)
        .operations(listOf(op))
        .build()
    every { stellarRpc.getTransaction(txHash) } returns txn

    val capturedEvent = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(capturedEvent)) } answers {}

    observer.fetchEvents()

    assertEquals(txHash, capturedEvent.captured.txHash)
    assertEquals(fromAccount, capturedEvent.captured.from)
    assertEquals(toAccount, capturedEvent.captured.to)
    assertEquals(amount, capturedEvent.captured.amount)
    assertEquals(paymentOpId, capturedEvent.captured.operationId)
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("CURSOR_MULTI", observer.cursor)
  }

  @Test
  fun `processTransferEvent skips and logs error when no creditable op matches operationIndex`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val txHash = "txHashSubInvocation"
    val seqNum = 300L
    val appOrder = 1

    setupTransferEvent(
      fromAccount,
      toAccount,
      BigInteger.valueOf(500_000L),
      txHash,
      operationIndex = 0L,
      "CURSOR_SUBINVOKE",
    )

    val txn =
      LedgerTransaction.builder()
        .hash(txHash)
        .sequenceNumber(seqNum)
        .applicationOrder(appOrder)
        .operations(emptyList())
        .build()
    every { stellarRpc.getTransaction(txHash) } returns txn

    assertDoesNotThrow { observer.fetchEvents() }

    verify(exactly = 0) { observer["handleEvent"](any<PaymentTransferEvent>()) }
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("CURSOR_SUBINVOKE", observer.cursor)
  }

  @Test
  fun `processTransferEvent retries getTransaction when RPC events index races ahead of transactions index`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val amount = BigInteger.valueOf(1_000_000L)
    val txHash = "txHashRaceCondition"
    val seqNum = 400L
    val appOrder = 1
    val paymentOpId = TOID(seqNum.toInt(), appOrder, 1).toInt64().toString()

    setupTransferEvent(fromAccount, toAccount, amount, txHash, operationIndex = 0L, "CURSOR_RACE")

    val paymentOp =
      LedgerTransaction.LedgerPaymentOperation().apply {
        from = fromAccount
        to = toAccount
        asset = Asset.createNativeAsset().toXdr()
        this.amount = amount
        id = paymentOpId
      }
    val op =
      LedgerOperation().apply {
        type = OperationType.PAYMENT
        paymentOperation = paymentOp
      }
    val txn =
      LedgerTransaction.builder()
        .hash(txHash)
        .sequenceNumber(seqNum)
        .applicationOrder(appOrder)
        .operations(listOf(op))
        .build()

    every { stellarRpc.getTransaction(txHash) } returnsMany listOf(null, txn)

    val capturedEvent = slot<PaymentTransferEvent>()
    every { observer["handleEvent"](capture(capturedEvent)) } answers {}

    observer.fetchEvents()

    verify(exactly = 2) { stellarRpc.getTransaction(txHash) }
    assertEquals(txHash, capturedEvent.captured.txHash)
    assertEquals(fromAccount, capturedEvent.captured.from)
    assertEquals(toAccount, capturedEvent.captured.to)
    assertEquals("CURSOR_RACE", observer.cursor)
  }

  private fun mockPoisonResponse(
    valueBase64: String,
    fromAccount: String = KeyPair.random().accountId,
    toSCVal: SCVal = Scv.toAddress(KeyPair.random().accountId),
  ): GetEventsResponse {
    val topics =
      listOf(
        Scv.toSymbol("transfer").toXdrBase64(),
        Scv.toAddress(fromAccount).toXdrBase64(),
        toSCVal.toXdrBase64(),
        Scv.toString("native").toXdrBase64(),
      )
    val event = mockk<GetEventsResponse.EventInfo>()
    every { event.topic } returns topics
    every { event.value } returns valueBase64

    val response = mockk<GetEventsResponse>()
    every { response.events } returns listOf(event)
    every { response.latestLedger } returns 777L
    every { response.cursor } returns "POISON_CUR"
    return response
  }

  private fun distributionAccounts(n: Int): List<String> {
    val accounts = List(n) { KeyPair.random().accountId }
    every { assetService.stellarAssets } returns
      accounts.map { acc -> StellarAssetInfo().apply { distributionAccount = acc } }
    return accounts
  }

  private fun scripted(sent: MutableList<GetEventsRequest>, vararg items: Any) {
    val queue = ArrayDeque(items.toList())
    every { sorobanServer.getEvents(capture(sent)) } answers
      {
        val next = queue.removeFirst()
        if (next is Throwable) throw next else next as GetEventsResponse
      }
  }

  private fun rejection() = SorobanRpcException(-32602, "too many filters", null)

  private fun emptyResponse(cursor: String): GetEventsResponse {
    val response = mockk<GetEventsResponse>()
    every { response.events } returns emptyList()
    every { response.latestLedger } returns 100L
    every { response.cursor } returns cursor
    return response
  }

  @Test
  fun `buildEventRequests splits 3 accounts into requests of 5 and 1 filters without losing any`() {
    val accounts = distributionAccounts(3)

    val requests = observer.buildEventRequests(null)

    assertEquals(listOf(5, 1), requests.map { it.filters.size })
    val to = requests.flatMap { it.filters }.map { f -> f.topics.toList()[0].toList() }
    assertEquals(6, to.size)
    accounts.forEachIndexed { i, acc ->
      assertEquals(Scv.toAddress(acc).toXdrBase64(), to[2 * i][1])
      assertEquals(Scv.toAddress(acc).toXdrBase64(), to[2 * i + 1][2])
    }
  }

  @Test
  fun `buildEventRequests sends ceil of 2N over 5 requests and none exceeds 5 filters`() {
    for ((n, expected) in listOf(1 to 1, 2 to 1, 5 to 2, 6 to 3, 7 to 3)) {
      distributionAccounts(n)
      val requests = observer.buildEventRequests(null)
      assertEquals(expected, requests.size, "accounts=$n")
      assertTrue(requests.all { it.filters.size <= 5 }, "accounts=$n")
      assertEquals(2 * n, requests.sumOf { it.filters.size }, "accounts=$n")
    }
  }

  @Test
  fun `buildEventRequests with one account sends one request with 2 filters`() {
    distributionAccounts(1)
    assertEquals(listOf(2), observer.buildEventRequests(null).map { it.filters.size })
  }

  @Test
  fun `buildEventRequests returns no request when there are no distribution accounts`() {
    every { assetService.stellarAssets } returns emptyList()
    assertTrue(observer.buildEventRequests("CUR").isEmpty())
  }

  @Test
  fun `buildEventRequests sends the stored cursor to every request`() {
    distributionAccounts(3)
    val requests = observer.buildEventRequests("0001")
    assertEquals(2, requests.size)
    requests.forEach {
      assertEquals("0001", it.pagination.cursor)
      assertNull(it.startLedger)
    }
  }

  @Test
  fun `buildEventRequests sends the same startLedger to every request when no cursor is stored`() {
    distributionAccounts(3)
    val requests = observer.buildEventRequests(null)
    requests.forEach {
      assertEquals(5L, it.startLedger)
      assertNull(it.pagination)
    }
  }

  @Test
  fun `fetchEvents persists the minimum cursor of the requests`() {
    distributionAccounts(3)
    every { sorobanServer.getEvents(any()) } returnsMany
      listOf(emptyResponse("0003"), emptyResponse("0002"))
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.cursor = "0001"

    observer.fetchEvents()

    verify(exactly = 2) { sorobanServer.getEvents(any()) }
    verify(exactly = 1) { paymentStreamerCursorStore.saveStellarRpcCursor("0002") }
    assertEquals("0002", observer.cursor)
  }

  @Test
  fun `fetchEvents holds the cursor at the sent cursor when one request fails`() {
    distributionAccounts(3)
    every { sorobanServer.getEvents(any()) } returns
      emptyResponse("0003") andThenThrows
      NetworkException(503, "rpc down")
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.cursor = "0001"

    observer.fetchEvents()

    assertEquals("0001", observer.cursor)
    verify(exactly = 0) { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
  }

  @Test
  fun `fetchEvents holds the cursor when the first request fails and the second succeeds`() {
    distributionAccounts(3)
    every { sorobanServer.getEvents(any()) } throws
      NetworkException(503, "rpc down") andThen
      emptyResponse("0003")
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.cursor = "0001"

    observer.fetchEvents()

    assertEquals("0001", observer.cursor)
    verify(exactly = 0) { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
  }

  @Test
  fun `fetchEvents holds the cursor when the first event of a request fails transiently`() {
    distributionAccounts(1)
    val e1 = eventWithId("e1")
    processAll()
    every { observer.processTransferEvent(any()) } throws NetworkException(503, "unavailable")
    val response = mockk<GetEventsResponse>()
    every { response.events } returns listOf(e1)
    every { response.latestLedger } returns 100L
    every { response.cursor } returns "0003"
    every { sorobanServer.getEvents(any()) } returns response
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.cursor = "0001"

    observer.fetchEvents()

    assertEquals("0001", observer.cursor)
    verify(exactly = 0) { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
  }

  @Test
  fun `fetchEvents leaves lastActivityTime alone during an outage`() {
    distributionAccounts(1)
    every { sorobanServer.getEvents(any()) } throws NetworkException(503, "outage")
    observer.cursor = "0001"
    val old = java.time.Instant.now().minusSeconds(60)
    observer.lastActivityTime = old

    observer.fetchEvents()

    assertEquals(old, observer.lastActivityTime)
  }

  @Test
  fun `fetchEvents does not save a cursor when no cursor was sent and one request fails`() {
    distributionAccounts(3)
    every { paymentStreamerCursorStore.loadStellarRpcCursor() } returns null
    every { sorobanServer.getEvents(any()) } returns
      emptyResponse("0003") andThenThrows
      NetworkException(503, "rpc down")

    observer.fetchEvents()

    verify(exactly = 0) { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
  }

  @Test
  fun `fetchEvents sends no request and stays healthy when there are no distribution accounts`() {
    every { assetService.stellarAssets } returns emptyList()
    observer.cursor = "0001"

    observer.fetchEvents()

    verify(exactly = 0) { sorobanServer.getEvents(any()) }
    verify(exactly = 0) { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
  }

  @Test
  fun `fetchEvents arms lastActivityTime on the first poll even when getEvents is rejected`() {
    distributionAccounts(3)
    every { sorobanServer.getEvents(any()) } throws rejection()
    observer.cursor = "0001"
    assertNull(observer.lastActivityTime)

    observer.fetchEvents()
    val armed = observer.lastActivityTime
    assertNotNull(armed)

    Thread.sleep(5)
    observer.fetchEvents()
    assertEquals(armed, observer.lastActivityTime)
  }

  @Test
  fun `a backend that never completes a poll trips the silence detector`() {
    distributionAccounts(3)
    every { sorobanServer.getEvents(any()) } throws NetworkException(503, "outage")
    observer.cursor = "0001"

    observer.fetchEvents()
    observer.lastActivityTime = java.time.Instant.now().minusSeconds(config.silenceTimeout + 1L)
    observer.checkSilence()

    assertEquals(ObserverStatus.SILENCE_ERROR, observer.getStatus())
  }

  @Test
  fun `fetchEvents sets STREAM_ERROR after 5 consecutive failed polls and not before`() {
    distributionAccounts(1)
    every { sorobanServer.getEvents(any()) } throws rejection()
    observer.cursor = "0001"

    repeat(4) { observer.fetchEvents() }
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())

    observer.fetchEvents()
    assertEquals(ObserverStatus.STREAM_ERROR, observer.getStatus())
  }

  @Test
  fun `fetchEvents resets the consecutive failure count after a successful poll`() {
    distributionAccounts(1)
    observer.cursor = "0001"
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }

    every { sorobanServer.getEvents(any()) } throws rejection()
    repeat(4) { observer.fetchEvents() }
    every { sorobanServer.getEvents(any()) } returns emptyResponse("0002")
    observer.fetchEvents()
    assertEquals(0, observer.consecutiveFailedPolls)

    every { sorobanServer.getEvents(any()) } throws rejection()
    repeat(4) { observer.fetchEvents() }
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
  }

  @Test
  fun `fetchEvents does not count an RPC outage as a rejection`() {
    distributionAccounts(1)
    every { sorobanServer.getEvents(any()) } throws NetworkException(503, "outage")
    observer.cursor = "0001"

    repeat(10) { observer.fetchEvents() }

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals(0, observer.consecutiveFailedPolls)
  }

  @Test
  fun `a failed latest-ledger lookup while building requests does not stop the observer`() {
    every { observer.buildEventRequests(any()) } throws IOException("cannot build")

    repeat(10) { observer.fetchEvents() }

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
  }

  @Test
  fun `check reports YELLOW while STARTING`() {
    val starting =
      StellarRpcPaymentObserver(
        stellarRpc,
        config,
        paymentListeners,
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
        sacToAssetMapper,
        assetService,
      )
    assertEquals(ObserverStatus.STARTING, starting.getStatus())
    assertEquals(HealthCheckStatus.YELLOW, starting.check().status)
    starting.setStatus(ObserverStatus.RUNNING)
    assertEquals(ObserverStatus.RUNNING, starting.getStatus())
  }

  @Test
  fun `check reports GREEN while RUNNING`() {
    assertEquals(HealthCheckStatus.GREEN, observer.check().status)
  }

  @Test
  fun `check reports RED once setStatus moves the observer into an error state`() {
    for (status in
      listOf(
        ObserverStatus.STREAM_ERROR,
        ObserverStatus.DATABASE_ERROR,
        ObserverStatus.SILENCE_ERROR,
        ObserverStatus.PUBLISHER_ERROR,
        ObserverStatus.NEEDS_SHUTDOWN,
        ObserverStatus.SHUTDOWN,
      )) {
      observer.status = ObserverStatus.RUNNING
      observer.setStatus(status)
      assertEquals(HealthCheckStatus.RED, observer.check().status, "status=$status")
    }
  }

  @Test
  fun `shutdownInternal does not move the status out of the supervisor's hands`() {
    observer.startInternal()
    observer.setStatus(ObserverStatus.STREAM_ERROR)

    observer.shutdownInternal()

    assertEquals(ObserverStatus.STREAM_ERROR, observer.getStatus())
  }

  private fun newStartingObserver() =
    spyk(
      StellarRpcPaymentObserver(
        stellarRpc,
        config,
        paymentListeners,
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
        sacToAssetMapper,
        assetService,
      ),
      recordPrivateCalls = true,
    )

  @Test
  fun `start promotes a STARTING observer to RUNNING`() {
    val starting = newStartingObserver()
    justRun { starting.startInternal() }

    starting.start()

    assertEquals(ObserverStatus.RUNNING, starting.getStatus())
    starting.silenceWatcher.shutdownNow()
    starting.statusWatcher.shutdownNow()
  }

  @Test
  fun `start does not overwrite an error status set while startInternal ran`() {
    val starting = newStartingObserver()
    every { starting.startInternal() } answers { starting.setStatus(ObserverStatus.DATABASE_ERROR) }

    starting.start()

    assertEquals(ObserverStatus.DATABASE_ERROR, starting.getStatus())
    starting.silenceWatcher.shutdownNow()
    starting.statusWatcher.shutdownNow()
  }

  @Test
  fun `startInternal resets the consecutive rejection count so each restart gets a fresh budget`() {
    observer.consecutiveFailedPolls = 4

    observer.startInternal()

    assertEquals(0, observer.consecutiveFailedPolls)
    observer.shutdownInternal()
  }

  @Test
  fun `the next poll resumes each request from its own cursor`() {
    distributionAccounts(3)
    val sent = mutableListOf<GetEventsRequest>()
    every { sorobanServer.getEvents(capture(sent)) } returnsMany
      listOf(
        emptyResponse("0003"),
        emptyResponse("0002"),
        emptyResponse("0004"),
        emptyResponse("0004"),
      )
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.cursor = "0001"

    observer.fetchEvents()
    observer.fetchEvents()

    assertEquals("0001", sent[0].pagination.cursor)
    assertEquals("0001", sent[1].pagination.cursor)
    assertEquals("0003", sent[2].pagination.cursor)
    assertEquals("0002", sent[3].pagination.cursor)
  }

  @Test
  fun `a failed request does not make the healthy request re-read its events`() {
    distributionAccounts(3)
    val sent = mutableListOf<GetEventsRequest>()
    scripted(
      sent,
      emptyResponse("0003"),
      NetworkException(503, "rpc down"),
      emptyResponse("0004"),
      NetworkException(503, "rpc down"),
    )
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.cursor = "0001"

    observer.fetchEvents()
    observer.fetchEvents()

    assertEquals("0003", sent[2].pagination.cursor)
    assertEquals("0001", sent[3].pagination.cursor)
    assertEquals("0001", observer.cursor)
    verify(exactly = 0) { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
  }

  @Test
  fun `a request without a cursor starts from startLedger while another resumes from its cursor`() {
    distributionAccounts(3)
    observer.chunkCursors[0] = "0003"
    observer.chunkCount = 2

    val requests = observer.buildEventRequests(null)

    assertEquals("0003", requests[0].pagination.cursor)
    assertEquals(5L, requests[1].startLedger)
  }

  @Test
  fun `startInternal forgets the per-request cursors so a restart re-reads from the persisted one`() {
    observer.chunkCursors[0] = "0003"

    observer.startInternal()

    assertTrue(observer.chunkCursors.isEmpty())
    observer.shutdownInternal()
  }

  @Test
  fun `a change in the number of requests drops the per-request cursors`() {
    distributionAccounts(3)
    observer.buildEventRequests("0001")
    observer.chunkCursors[0] = "0003"

    distributionAccounts(6)
    assertEquals(3, observer.buildEventRequests("0001").size)

    assertTrue(observer.chunkCursors.isEmpty())
  }

  @Test
  fun `fetchEvents does not reset the latest block read metric when there are no accounts`() {
    every { assetService.stellarAssets } returns emptyList()
    observer.cursor = "0001"
    observer.metricLatestBlockRead.set(7)

    observer.fetchEvents()

    assertEquals(7, observer.metricLatestBlockRead.get())
  }

  @Test
  fun `an outage between rejections leaves the rejection count as it was`() {
    distributionAccounts(1)
    observer.cursor = "0001"

    every { sorobanServer.getEvents(any()) } throws rejection()
    repeat(3) { observer.fetchEvents() }
    assertEquals(3, observer.consecutiveFailedPolls)

    every { sorobanServer.getEvents(any()) } throws NetworkException(503, "outage")
    repeat(4) { observer.fetchEvents() }
    assertEquals(3, observer.consecutiveFailedPolls)
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())

    every { sorobanServer.getEvents(any()) } throws rejection()
    observer.fetchEvents()
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    observer.fetchEvents()
    assertEquals(ObserverStatus.STREAM_ERROR, observer.getStatus())
  }

  @Test
  fun `promoteIfStarting moves STARTING to RUNNING and leaves any other status alone`() {
    val starting = newStartingObserver()
    starting.promoteIfStarting()
    assertEquals(ObserverStatus.RUNNING, starting.getStatus())

    val failed = newStartingObserver()
    failed.setStatus(ObserverStatus.STREAM_ERROR)
    failed.promoteIfStarting()
    assertEquals(ObserverStatus.STREAM_ERROR, failed.getStatus())
  }

  @Test
  fun `a request that fails after it advanced holds its own cursor not the persisted one`() {
    distributionAccounts(3)
    val sent = mutableListOf<GetEventsRequest>()
    scripted(
      sent,
      emptyResponse("0002"),
      emptyResponse("0003"),
      emptyResponse("0004"),
      NetworkException(503, "rpc down"),
      emptyResponse("0005"),
      emptyResponse("0004"),
    )
    justRun { paymentStreamerCursorStore.saveStellarRpcCursor(any()) }
    observer.cursor = "0001"

    repeat(3) { observer.fetchEvents() }

    assertEquals("0003", sent[3].pagination.cursor)
    assertEquals("0003", sent[5].pagination.cursor)
    verify(exactly = 1) { paymentStreamerCursorStore.saveStellarRpcCursor("0003") }
  }
}

/**
 * This class is a mock implementation of SacToAssetMapper for testing purposes because
 * SacToAssetMapper.getAssetFromSac cannot be mocked directly.
 *
 * Mocking SacToAssetMapper throws an exception: i.m.p.j.t.JvmInlineInstrumentation - Failed to
 * transform classes
 * [class org.stellar.sdk.xdr.Asset, interface org.stellar.sdk.xdr.XdrElement, class java.lang.Object]
 */
internal class MockSacToAssetMapper : SacToAssetMapper(null) {
  var resolvableContracts: Set<String>? = null

  override fun getAssetFromSac(sac: String): org.stellar.sdk.xdr.Asset? {
    val allowed = resolvableContracts
    if (allowed != null && sac !in allowed) {
      return null
    }
    return Asset.createNativeAsset().toXdr()
  }
}
