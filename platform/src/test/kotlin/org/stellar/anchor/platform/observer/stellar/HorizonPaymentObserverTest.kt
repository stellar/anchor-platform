package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.LedgerException
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.data.JdbcSep24TransactionStore
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.data.JdbcSep31TransactionStore
import org.stellar.anchor.platform.data.JdbcSep6TransactionStore
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.anchor.platform.observer.stellar.AbstractPaymentObserver.ObserverStatus
import org.stellar.anchor.util.AssetHelper
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MuxedAccount
import org.stellar.sdk.responses.operations.InvokeHostFunctionOperationResponse
import org.stellar.sdk.responses.operations.InvokeHostFunctionOperationResponse.HostFunctionParameter
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse
import org.stellar.sdk.xdr.Memo
import org.stellar.sdk.xdr.MemoType
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.SCSymbol
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType
import org.stellar.sdk.xdr.XdrString

class HorizonPaymentObserverTest {
  private lateinit var horizon: Horizon
  private lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager
  private lateinit var paymentStreamerCursorStore: StellarPaymentStreamerCursorStore
  private lateinit var observer: HorizonPaymentObserver

  @BeforeEach
  fun setUp() {
    horizon = mockk(relaxed = true)
    paymentObservingAccountsManager = mockk(relaxed = true)
    paymentStreamerCursorStore = mockk(relaxed = true)
    val config =
      StellarPaymentObserverConfig().apply {
        silenceCheckInterval = 60
        silenceTimeout = 300
        silenceTimeoutRetries = 3
        initialStreamBackoffTime = 1000
        maxStreamBackoffTime = 10000
        initialEventBackoffTime = 500
        maxEventBackoffTime = 5000
      }
    observer =
      HorizonPaymentObserver(
        horizon,
        config,
        emptyList<PaymentListener>(),
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
      )
  }

  @Test
  fun `toPaymentTransferEvent returns event for PaymentOperationResponse when account is observed`() {
    val op = mockk<PaymentOperationResponse>()
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    every { op.to } returns toAccount
    every { op.from } returns fromAccount
    every { op.asset } returns Asset.createNativeAsset()
    every { op.amount } returns "100.0"
    every { op.id } returns 123L
    every { op.transactionHash } returns "txHash"
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    every { horizon.getTransaction(any()) } returns mockk()

    val event = observer.toPaymentTransferEvent(op)

    assertNotNull(event)
    assertEquals(fromAccount, event?.from)
    assertEquals(toAccount, event?.to)
    assertEquals("native", event?.sep11Asset)
    assertEquals(AssetHelper.toXdrAmount("100.0").toBigInteger(), event?.amount)
    assertEquals("123", event?.operationId)
    assertEquals("txHash", event?.txHash)
  }

  @Test
  fun `toPaymentTransferEvent returns event for PathPaymentBaseOperationResponse when account is observed`() {
    val op = mockk<PathPaymentBaseOperationResponse>()
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId

    every { op.to } returns toAccount
    every { op.from } returns fromAccount
    every { op.asset } returns Asset.createNativeAsset()
    every { op.amount } returns "200.0"
    every { op.id } returns 456L
    every { op.transactionHash } returns "txHash2"
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    every { horizon.getTransaction(any()) } returns mockk()

    val event = observer.toPaymentTransferEvent(op)

    assertNotNull(event)
    assertEquals(fromAccount, event?.from)
    assertEquals(toAccount, event?.to)
    assertEquals("native", event?.sep11Asset)
    assertEquals(AssetHelper.toXdrAmount("200.0").toBigInteger(), event?.amount)
    assertEquals("456", event?.operationId)
    assertEquals("txHash2", event?.txHash)
  }

  @Test
  fun `toPaymentTransferEvent returns event for InvokeHostFunctionOperationResponse with transfer function`() {
    val assetBalanceChange = mockk<InvokeHostFunctionOperationResponse.AssetContractBalanceChange>()
    every { assetBalanceChange.type } returns "transfer"
    every { assetBalanceChange.destinationMuxedId } returns null
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId

    every { assetBalanceChange.from } returns fromAccount
    every { assetBalanceChange.to } returns toAccount
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true

    every { assetBalanceChange.asset } returns Asset.createNativeAsset()
    every { assetBalanceChange.amount } returns "300.0"
    val invokeOp = mockk<InvokeHostFunctionOperationResponse>()
    every { invokeOp.function } returns "HostFunctionTypeHostFunctionTypeInvokeContract"
    every { invokeOp.assetBalanceChanges } returns listOf(assetBalanceChange)
    every { invokeOp.transactionHash } returns "txHash3"
    every { invokeOp.id } returns 789L
    val transferXdr =
      SCVal.builder()
        .discriminant(SCValType.SCV_SYMBOL)
        .sym(SCSymbol(XdrString("transfer")))
        .build()
        .toXdrBase64()
    every { invokeOp.parameters } returns
      List(5) { HostFunctionParameter("mock type", transferXdr) }
    every { horizon.getTransaction(any()) } returns
      LedgerTransaction.builder().hash("txHash").operations(listOf()).build()

    val event = observer.toPaymentTransferEvent(invokeOp)

    assertNotNull(event)
    assertEquals(fromAccount, event?.from)
    assertEquals(toAccount, event?.to)
    assertEquals("native", event?.sep11Asset)
    assertEquals(AssetHelper.toXdrAmount("300.0").toBigInteger(), event?.amount)
    assertEquals("789", event?.operationId)
    assertEquals("txHash3", event?.txHash)
  }

  @Test
  fun `toPaymentTransferEvent returns null for unobserved PaymentOperationResponse`() {
    val op = mockk<PaymentOperationResponse>()
    every { op.to } returns "toAccount"
    every { op.from } returns "fromAccount"
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns false

    val event = observer.toPaymentTransferEvent(op)
    assertNull(event)
  }

  @Test
  fun `toPaymentTransferEvent returns null for unknown operation type`() {
    val op = mockk<OperationResponse>()
    val event = observer.toPaymentTransferEvent(op)
    assertNull(event)
  }

  @Test
  fun `toPaymentTransferEvent returns null instead of throwing when a contract call carries no balance changes`() {
    val invokeOp = mockk<InvokeHostFunctionOperationResponse>()
    every { invokeOp.assetBalanceChanges } returns emptyList()

    val event = observer.toPaymentTransferEvent(invokeOp)
    assertNull(event)
  }

  @Test
  fun `toPaymentTransferEvent finds the observed balance change even when it is not the first entry`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val unrelatedChange = mockk<InvokeHostFunctionOperationResponse.AssetContractBalanceChange>()
    every { unrelatedChange.type } returns "transfer"
    every { unrelatedChange.destinationMuxedId } returns null
    every { unrelatedChange.from } returns KeyPair.random().accountId
    every { unrelatedChange.to } returns KeyPair.random().accountId

    val observedChange = mockk<InvokeHostFunctionOperationResponse.AssetContractBalanceChange>()
    every { observedChange.type } returns "transfer"
    every { observedChange.destinationMuxedId } returns null
    every { observedChange.from } returns fromAccount
    every { observedChange.to } returns toAccount
    every { observedChange.asset } returns Asset.createNativeAsset()
    every { observedChange.amount } returns "50.0"

    every { paymentObservingAccountsManager.lookupAndUpdate(unrelatedChange.from) } returns false
    every { paymentObservingAccountsManager.lookupAndUpdate(unrelatedChange.to) } returns false
    every { paymentObservingAccountsManager.lookupAndUpdate(fromAccount) } returns true
    every { paymentObservingAccountsManager.lookupAndUpdate(toAccount) } returns true

    val invokeOp = mockk<InvokeHostFunctionOperationResponse>()
    every { invokeOp.assetBalanceChanges } returns listOf(unrelatedChange, observedChange)
    every { invokeOp.transactionHash } returns "txHash4"
    every { invokeOp.id } returns 321L
    every { horizon.getTransaction(any()) } returns
      LedgerTransaction.builder().hash("txHash").operations(listOf()).build()

    val event = observer.toPaymentTransferEvent(invokeOp)

    assertNotNull(event)
    assertEquals(fromAccount, event?.from)
    assertEquals(toAccount, event?.to)
    assertEquals("321", event?.operationId)
  }

  @Test
  fun `toPaymentTransferEvent captures a contract transfer regardless of the entrypoint name`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val assetBalanceChange = mockk<InvokeHostFunctionOperationResponse.AssetContractBalanceChange>()
    every { assetBalanceChange.type } returns "transfer"
    every { assetBalanceChange.destinationMuxedId } returns null
    every { assetBalanceChange.from } returns fromAccount
    every { assetBalanceChange.to } returns toAccount
    every { assetBalanceChange.asset } returns Asset.createNativeAsset()
    every { assetBalanceChange.amount } returns "10.0"
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true

    val invokeOp = mockk<InvokeHostFunctionOperationResponse>()
    every { invokeOp.function } returns "pay"
    every { invokeOp.assetBalanceChanges } returns listOf(assetBalanceChange)
    every { invokeOp.transactionHash } returns "txHash5"
    every { invokeOp.id } returns 654L
    every { invokeOp.parameters } returns null
    every { horizon.getTransaction(any()) } returns
      LedgerTransaction.builder().hash("txHash").operations(listOf()).build()

    val event = observer.toPaymentTransferEvent(invokeOp)

    assertNotNull(event)
    assertEquals(fromAccount, event?.from)
    assertEquals(toAccount, event?.to)
  }

  private fun contractTransfer(
    type: String = "transfer",
    from: String = KeyPair.random().accountId,
    to: String = KeyPair.random().accountId,
    muxedId: BigInteger? = null,
  ): InvokeHostFunctionOperationResponse {
    val change = mockk<InvokeHostFunctionOperationResponse.AssetContractBalanceChange>()
    every { change.type } returns type
    every { change.destinationMuxedId } returns muxedId
    every { change.from } returns from
    every { change.to } returns to
    every { change.asset } returns Asset.createNativeAsset()
    every { change.amount } returns "10.0"
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true

    val invokeOp = mockk<InvokeHostFunctionOperationResponse>()
    every { invokeOp.assetBalanceChanges } returns listOf(change)
    every { invokeOp.transactionHash } returns "txHash6"
    every { invokeOp.id } returns 777L
    return invokeOp
  }

  @Test
  fun `toPaymentTransferEvent ignores balance changes that are not transfers`() {
    every { horizon.getTransaction(any()) } returns
      LedgerTransaction.builder().hash("txHash").operations(listOf()).build()

    assertNull(observer.toPaymentTransferEvent(contractTransfer(type = "mint")))
  }

  @Test
  fun `toPaymentTransferEvent carries the destination muxed id into the recipient address`() {
    val toAccount = KeyPair.random().accountId
    every { horizon.getTransaction(any()) } returns
      LedgerTransaction.builder().hash("txHash").operations(listOf()).build()

    val event =
      observer.toPaymentTransferEvent(
        contractTransfer(to = toAccount, muxedId = BigInteger.valueOf(42))
      )

    assertEquals(MuxedAccount(toAccount, BigInteger.valueOf(42)).address, event?.to)
  }

  @Test
  fun `toPaymentTransferEvent adds the contract call to the transaction when the parser did not model it`() {
    every { horizon.getTransaction(any()) } returns
      LedgerTransaction.builder().hash("txHash").operations(listOf()).build()

    val event = observer.toPaymentTransferEvent(contractTransfer())

    val ops = event!!.ledgerTransaction.operations
    assertEquals(1, ops.size)
    assertEquals(OperationType.INVOKE_HOST_FUNCTION, ops[0].type)
    assertEquals("777", ops[0].invokeHostFunctionOperation.id)
  }

  @Test
  fun `toPaymentTransferEvent throws so the operation is retried when the transaction is not yet available`() {
    every { horizon.getTransaction(any()) } returns null

    assertThrows(LedgerException::class.java) {
      observer.toPaymentTransferEvent(contractTransfer())
    }
  }

  @Test
  fun `processOperation keeps the cursor and goes to PUBLISHER_ERROR when a listener throws IOException`() {
    val listener = mockk<PaymentListener>()
    every { listener.onReceived(any()) } throws IOException("lookup failed")
    val retryingObserver =
      HorizonPaymentObserver(
        horizon,
        StellarPaymentObserverConfig().apply {
          initialEventBackoffTime = 500
          maxEventBackoffTime = 5000
          initialStreamBackoffTime = 1000
          maxStreamBackoffTime = 10000
        },
        listOf(listener),
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
      )
    val op = mockk<PaymentOperationResponse>()
    every { op.to } returns KeyPair.random().accountId
    every { op.from } returns KeyPair.random().accountId
    every { op.asset } returns Asset.createNativeAsset()
    every { op.amount } returns "100.0"
    every { op.id } returns 123L
    every { op.pagingToken } returns "123"
    every { op.transactionHash } returns "txHash"
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    every { horizon.getTransaction(any()) } returns mockk()

    retryingObserver.processOperation(op)

    verify(exactly = 1) { listener.onReceived(any()) }
    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
    assertEquals(AbstractPaymentObserver.ObserverStatus.PUBLISHER_ERROR, retryingObserver.status)
  }

  private fun observerWith(listener: PaymentListener): HorizonPaymentObserver =
    HorizonPaymentObserver(
      horizon,
      StellarPaymentObserverConfig().apply {
        silenceCheckInterval = 60
        silenceTimeout = 300
        silenceTimeoutRetries = 3
        initialStreamBackoffTime = 1000
        maxStreamBackoffTime = 10000
        initialEventBackoffTime = 500
        maxEventBackoffTime = 5000
      },
      listOf(listener),
      paymentObservingAccountsManager,
      paymentStreamerCursorStore,
    )

  private fun observedPayment(): PaymentOperationResponse {
    val op = mockk<PaymentOperationResponse>()
    every { op.to } returns KeyPair.random().accountId
    every { op.from } returns KeyPair.random().accountId
    every { op.asset } returns Asset.createNativeAsset()
    every { op.amount } returns "100.0"
    every { op.id } returns 123L
    every { op.transactionHash } returns "txHash"
    every { op.pagingToken } returns "999"
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    return op
  }

  private fun observedPathPayment(): PathPaymentBaseOperationResponse {
    val op = mockk<PathPaymentBaseOperationResponse>()
    every { op.to } returns KeyPair.random().accountId
    every { op.from } returns KeyPair.random().accountId
    every { op.asset } returns Asset.createNativeAsset()
    every { op.amount } returns "200.0"
    every { op.id } returns 456L
    every { op.transactionHash } returns "txHash2"
    every { op.pagingToken } returns "1000"
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    return op
  }

  @Test
  fun `processOperation holds the cursor when the transaction lookup of a PAYMENT fails`() {
    val listener = mockk<PaymentListener>(relaxed = true)
    val observer = observerWith(listener)
    every { horizon.getTransaction(any()) } throws LedgerException("Error getting transaction")

    observer.processOperation(observedPayment())

    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
    verify(exactly = 0) { listener.onReceived(any()) }
    assertEquals(ObserverStatus.PUBLISHER_ERROR, observer.status)
  }

  @Test
  fun `processOperation holds the cursor when the transaction lookup of a PATH_PAYMENT fails`() {
    val listener = mockk<PaymentListener>(relaxed = true)
    val observer = observerWith(listener)
    every { horizon.getTransaction(any()) } throws LedgerException("Error getting transaction")

    observer.processOperation(observedPathPayment())

    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
    verify(exactly = 0) { listener.onReceived(any()) }
    assertEquals(ObserverStatus.PUBLISHER_ERROR, observer.status)
  }

  @Test
  fun `processOperation dispatches the event and saves the cursor once the transaction lookup recovers`() {
    val listener = mockk<PaymentListener>(relaxed = true)
    val observer = observerWith(listener)
    val op = observedPayment()
    every { horizon.getTransaction(any()) } throws LedgerException("Error getting transaction")

    observer.processOperation(op)
    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }

    every { horizon.getTransaction(any()) } returns mockk()
    observer.processOperation(op)

    verify(exactly = 1) { listener.onReceived(match { it.operationId == "123" }) }
    verify(exactly = 1) { paymentStreamerCursorStore.saveHorizonCursor("999") }
  }

  // ---- ANCHOR-1364: one event per qualifying balance change of a contract operation ----

  private fun balanceChange(
    from: String,
    to: String,
    amount: String,
    type: String = "transfer",
  ): InvokeHostFunctionOperationResponse.AssetContractBalanceChange {
    val change = mockk<InvokeHostFunctionOperationResponse.AssetContractBalanceChange>()
    every { change.type } returns type
    every { change.destinationMuxedId } returns null
    every { change.from } returns from
    every { change.to } returns to
    every { change.asset } returns Asset.createNativeAsset()
    every { change.amount } returns amount
    return change
  }

  private fun contractOperation(
    vararg changes: InvokeHostFunctionOperationResponse.AssetContractBalanceChange
  ): InvokeHostFunctionOperationResponse {
    val invokeOp = mockk<InvokeHostFunctionOperationResponse>()
    every { invokeOp.assetBalanceChanges } returns changes.toList()
    every { invokeOp.transactionHash } returns "txHash6"
    every { invokeOp.id } returns 777L
    every { invokeOp.pagingToken } returns "555"
    return invokeOp
  }

  private fun textMemo(text: String): Memo =
    Memo().apply {
      discriminant = MemoType.MEMO_TEXT
      this.text = XdrString(text)
    }

  private fun givenTransactionAvailable() {
    every { horizon.getTransaction(any()) } returns
      LedgerTransaction.builder().hash("txHash6").memo(textMemo("m1")).operations(listOf()).build()
  }

  @Test
  fun `toPaymentTransferEvents returns one event per qualifying transfer with its own values`() {
    val sender = KeyPair.random().accountId
    val firstTo = KeyPair.random().accountId
    val secondTo = KeyPair.random().accountId
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    givenTransactionAvailable()

    val events =
      observer.toPaymentTransferEvents(
        contractOperation(
          balanceChange(sender, firstTo, "10.0"),
          balanceChange(sender, secondTo, "20.0"),
        )
      )

    assertEquals(2, events.size)
    assertEquals(sender, events[0].from)
    assertEquals(firstTo, events[0].to)
    assertEquals(AssetHelper.toXdrAmount("10.0").toBigInteger(), events[0].amount)
    assertEquals(sender, events[1].from)
    assertEquals(secondTo, events[1].to)
    assertEquals(AssetHelper.toXdrAmount("20.0").toBigInteger(), events[1].amount)
    events.forEach {
      assertEquals("native", it.sep11Asset)
      assertEquals("777", it.operationId)
      assertEquals("txHash6", it.txHash)
    }
  }

  @Test
  fun `toPaymentTransferEvents returns exactly one event for a single qualifying transfer`() {
    val from = KeyPair.random().accountId
    val to = KeyPair.random().accountId
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    givenTransactionAvailable()

    val events =
      observer.toPaymentTransferEvents(contractOperation(balanceChange(from, to, "10.0")))

    assertEquals(1, events.size)
    assertEquals(from, events[0].from)
    assertEquals(to, events[0].to)
    assertEquals(AssetHelper.toXdrAmount("10.0").toBigInteger(), events[0].amount)
    assertEquals("777", events[0].operationId)
  }

  @Test
  fun `toPaymentTransferEvents skips unqualified balance changes and keeps the qualifying ones`() {
    val observedFrom = KeyPair.random().accountId
    val observedTo = KeyPair.random().accountId
    val strangerFrom = KeyPair.random().accountId
    val strangerTo = KeyPair.random().accountId
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns false
    every { paymentObservingAccountsManager.lookupAndUpdate(observedTo) } returns true
    givenTransactionAvailable()

    val events =
      observer.toPaymentTransferEvents(
        contractOperation(
          balanceChange(observedFrom, observedTo, "1.0", type = "mint"),
          balanceChange("", observedTo, "2.0"),
          balanceChange(observedFrom, "", "3.0"),
          balanceChange(strangerFrom, strangerTo, "4.0"),
          balanceChange(strangerFrom, observedTo, "5.0"),
          balanceChange(observedFrom, observedTo, "6.0"),
        )
      )

    assertEquals(
      listOf(
        AssetHelper.toXdrAmount("5.0").toBigInteger(),
        AssetHelper.toXdrAmount("6.0").toBigInteger(),
      ),
      events.map { it.amount },
    )
  }

  @Test
  fun `toPaymentTransferEvents returns no event and does not fetch the transaction when nothing qualifies`() {
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns false

    val events =
      observer.toPaymentTransferEvents(
        contractOperation(
          balanceChange(KeyPair.random().accountId, KeyPair.random().accountId, "1.0")
        )
      )

    assertTrue(events.isEmpty())
    verify(exactly = 0) { horizon.getTransaction(any()) }
  }

  @Test
  fun `toPaymentTransferEvents fetches the ledger transaction once for a multi-event operation`() {
    val sender = KeyPair.random().accountId
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    givenTransactionAvailable()

    observer.toPaymentTransferEvents(
      contractOperation(
        balanceChange(sender, KeyPair.random().accountId, "1.0"),
        balanceChange(sender, KeyPair.random().accountId, "2.0"),
        balanceChange(sender, KeyPair.random().accountId, "3.0"),
      )
    )

    verify(exactly = 1) { horizon.getTransaction("txHash6") }
  }

  @Test
  fun `toPaymentTransferEvents throws when the transaction is not available for a qualifying operation`() {
    val sender = KeyPair.random().accountId
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    every { horizon.getTransaction(any()) } returns null

    assertThrows(LedgerException::class.java) {
      observer.toPaymentTransferEvents(
        contractOperation(
          balanceChange(sender, KeyPair.random().accountId, "1.0"),
          balanceChange(sender, KeyPair.random().accountId, "2.0"),
        )
      )
    }
  }

  @Test
  fun `processOperation holds the cursor when the transaction of a contract operation is not available`() {
    val listener = mockk<PaymentListener>(relaxed = true)
    val observer = observerWith(listener)
    val sender = KeyPair.random().accountId
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    every { horizon.getTransaction(any()) } returns null

    observer.processOperation(
      contractOperation(balanceChange(sender, KeyPair.random().accountId, "1.0"))
    )

    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
    verify(exactly = 0) { listener.onReceived(any()) }
    assertEquals(ObserverStatus.PUBLISHER_ERROR, observer.status)
  }

  @Test
  fun `processOperation saves the cursor and dispatches nothing when no balance change qualifies`() {
    val listener = mockk<PaymentListener>(relaxed = true)
    val observer = observerWith(listener)
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns false

    observer.processOperation(
      contractOperation(
        balanceChange(KeyPair.random().accountId, KeyPair.random().accountId, "1.0")
      )
    )

    verify(exactly = 0) { listener.onReceived(any()) }
    verify(exactly = 1) { paymentStreamerCursorStore.saveHorizonCursor("555") }
  }

  @Test
  fun `processOperation dispatches every event in order and saves the cursor once after the last`() {
    val listener = mockk<PaymentListener>(relaxed = true)
    val observer = observerWith(listener)
    val sender = KeyPair.random().accountId
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    givenTransactionAvailable()

    observer.processOperation(
      contractOperation(
        balanceChange(sender, KeyPair.random().accountId, "10.0"),
        balanceChange(sender, KeyPair.random().accountId, "20.0"),
      )
    )

    verifyOrder {
      listener.onReceived(match { it.amount == AssetHelper.toXdrAmount("10.0").toBigInteger() })
      listener.onReceived(match { it.amount == AssetHelper.toXdrAmount("20.0").toBigInteger() })
      paymentStreamerCursorStore.saveHorizonCursor("555")
    }
    verify(exactly = 2) { listener.onReceived(any()) }
    verify(exactly = 1) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
  }

  @Test
  fun `processOperation stops at the event whose listener fails and does not save the cursor`() {
    val listener = mockk<PaymentListener>()
    every { listener.onReceived(any()) } throws IOException("platform unavailable")
    val observer = observerWith(listener)
    val sender = KeyPair.random().accountId
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    givenTransactionAvailable()

    observer.processOperation(
      contractOperation(
        balanceChange(sender, KeyPair.random().accountId, "10.0"),
        balanceChange(sender, KeyPair.random().accountId, "20.0"),
      )
    )

    verify(exactly = 1) { listener.onReceived(any()) }
    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
    assertEquals(ObserverStatus.PUBLISHER_ERROR, observer.status)
  }

  /** The real listener over stores that stop matching a transaction once it was credited. */
  private class CreditingListenerFixture(
    accountsManager: PaymentObservingAccountsManager,
    val pendingByAccount: MutableMap<String, MutableList<JdbcSep31Transaction>>,
  ) {
    val platformApiClient = mockk<PlatformApiClient>(relaxed = true)
    private val sep31Store = mockk<JdbcSep31TransactionStore>()
    val listener: DefaultPaymentListener

    init {
      every { sep31Store.findAllByToAccountAndMemoAndStatus(any(), any(), any()) } answers
        {
          pendingByAccount[firstArg<String>()]?.toList() ?: emptyList()
        }
      every { platformApiClient.notifyOnchainFundsReceived(any(), any(), any(), any()) } answers
        {
          pendingByAccount.values.forEach { txns -> txns.removeIf { it.id == firstArg<String>() } }
        }
      listener =
        DefaultPaymentListener(
          accountsManager,
          sep31Store,
          mockk<JdbcSep24TransactionStore>(relaxed = true),
          mockk<JdbcSep6TransactionStore>(relaxed = true),
          platformApiClient,
          mockk<RpcConfig>(relaxed = true),
          mockk<AssetService>(relaxed = true),
        )
    }
  }

  private fun pendingSep31(id: String) =
    JdbcSep31Transaction().apply {
      this.id = id
      amountInAsset = "stellar:native"
    }

  @Test
  fun `a second transfer of the same operation is credited with its own amount and destination`() {
    val sender = KeyPair.random().accountId
    val firstTo = KeyPair.random().accountId
    val secondTo = KeyPair.random().accountId
    val fixture =
      CreditingListenerFixture(
        paymentObservingAccountsManager,
        mutableMapOf(
          firstTo to mutableListOf(pendingSep31("txn-1")),
          secondTo to mutableListOf(pendingSep31("txn-2")),
        ),
      )
    val observer = observerWith(fixture.listener)
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    givenTransactionAvailable()

    observer.processOperation(
      contractOperation(
        balanceChange(sender, firstTo, "10.0"),
        balanceChange(sender, secondTo, "20.0"),
      )
    )

    verify(exactly = 1) {
      fixture.platformApiClient.notifyOnchainFundsReceived("txn-1", "txHash6", "10.0000000", any())
    }
    verify(exactly = 1) {
      fixture.platformApiClient.notifyOnchainFundsReceived("txn-2", "txHash6", "20.0000000", any())
    }
    verify(exactly = 1) { paymentStreamerCursorStore.saveHorizonCursor("555") }
  }

  @Test
  fun `redelivery after a partial dispatch does not credit an already credited transaction again`() {
    val sender = KeyPair.random().accountId
    val firstTo = KeyPair.random().accountId
    val secondTo = KeyPair.random().accountId
    val fixture =
      CreditingListenerFixture(
        paymentObservingAccountsManager,
        mutableMapOf(
          firstTo to mutableListOf(pendingSep31("txn-1")),
          secondTo to mutableListOf(pendingSep31("txn-2")),
        ),
      )
    // fails on the second event of the first attempt only
    var received = 0
    val flaky = mockk<PaymentListener>()
    every { flaky.onReceived(any()) } answers
      {
        received++
        if (received == 2) throw IOException("platform unavailable")
      }
    val observer =
      HorizonPaymentObserver(
        horizon,
        StellarPaymentObserverConfig().apply {
          silenceCheckInterval = 60
          silenceTimeout = 300
          silenceTimeoutRetries = 3
          initialStreamBackoffTime = 1000
          maxStreamBackoffTime = 10000
          initialEventBackoffTime = 500
          maxEventBackoffTime = 5000
        },
        listOf(fixture.listener, flaky),
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
      )
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    givenTransactionAvailable()
    val operation =
      contractOperation(
        balanceChange(sender, firstTo, "10.0"),
        balanceChange(sender, secondTo, "20.0"),
      )

    observer.processOperation(operation)
    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }

    observer.processOperation(operation)

    verify(exactly = 1) {
      fixture.platformApiClient.notifyOnchainFundsReceived("txn-1", any(), any(), any())
    }
    verify(exactly = 1) {
      fixture.platformApiClient.notifyOnchainFundsReceived("txn-2", any(), any(), any())
    }
    verify(exactly = 1) { paymentStreamerCursorStore.saveHorizonCursor("555") }
  }

  // ---- ANCHOR-1364: a classic payment whose transaction is not yet available ----

  @Test
  fun `processOperation holds the cursor when the transaction of a PAYMENT is not yet available`() {
    val listener = mockk<PaymentListener>(relaxed = true)
    val observer = observerWith(listener)
    every { horizon.getTransaction(any()) } returns null

    observer.processOperation(observedPayment())

    verify(exactly = 0) { listener.onReceived(any()) }
    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
    assertEquals(ObserverStatus.PUBLISHER_ERROR, observer.status)
  }

  @Test
  fun `processOperation holds the cursor when the transaction of a PATH_PAYMENT is not yet available`() {
    val listener = mockk<PaymentListener>(relaxed = true)
    val observer = observerWith(listener)
    every { horizon.getTransaction(any()) } returns null

    observer.processOperation(observedPathPayment())

    verify(exactly = 0) { listener.onReceived(any()) }
    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
    assertEquals(ObserverStatus.PUBLISHER_ERROR, observer.status)
  }

  @Test
  fun `processOperation credits a PAYMENT and saves the cursor once its transaction becomes available`() {
    val listener = mockk<PaymentListener>(relaxed = true)
    val observer = observerWith(listener)
    val op = observedPayment()
    every { horizon.getTransaction(any()) } returns null

    observer.processOperation(op)
    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }

    every { horizon.getTransaction(any()) } returns mockk()
    observer.processOperation(op)

    verify(exactly = 1) { listener.onReceived(match { it.operationId == "123" }) }
    verify(exactly = 1) { paymentStreamerCursorStore.saveHorizonCursor("999") }
  }

  @Test
  fun `toPaymentTransferEvent names the transaction hash when a classic payment transaction is not available`() {
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true
    every { horizon.getTransaction(any()) } returns null

    val payment =
      assertThrows(LedgerException::class.java) {
        observer.toPaymentTransferEvent(observedPayment())
      }
    val pathPayment =
      assertThrows(LedgerException::class.java) {
        observer.toPaymentTransferEvent(observedPathPayment())
      }

    assertTrue(payment.message!!.contains("txHash"))
    assertTrue(pathPayment.message!!.contains("txHash2"))
  }
}
