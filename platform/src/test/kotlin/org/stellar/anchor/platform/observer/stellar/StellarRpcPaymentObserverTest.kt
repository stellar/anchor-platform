package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.io.IOException
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.asset.AssetService
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
    observer.processOperation(ledgerTxn, op)

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
    observer.processOperation(ledgerTxn, op)

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
    observer.processOperation(ledgerTxn, op)

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
    observer.processOperation(ledgerTxn, op)

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
  fun `test buildEventRequest`() {
    val account1 = KeyPair.random().accountId
    val account2 = KeyPair.random().accountId
    // Mock the behavior of getStellarAssets()
    every { assetService.stellarAssets } returns
      listOf(
        StellarAssetInfo().apply { distributionAccount = account1 },
        StellarAssetInfo().apply { distributionAccount = account2 },
      )

    // Call the method under test
    val request = observer.buildEventRequest(null)

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
    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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
    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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
    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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
    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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
    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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
    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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

    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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

    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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

    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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
    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
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
  override fun getAssetFromSac(sac: String): org.stellar.sdk.xdr.Asset {
    return Asset.createNativeAsset().toXdr() // Mocking to return a native asset for simplicity
  }
}
