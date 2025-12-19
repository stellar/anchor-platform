package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.anchor.util.AssetHelper
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.responses.operations.InvokeHostFunctionOperationResponse
import org.stellar.sdk.responses.operations.InvokeHostFunctionOperationResponse.HostFunctionParameter
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse
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
    every { horizon.getTransaction(any()) } returns mockk()

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
}
