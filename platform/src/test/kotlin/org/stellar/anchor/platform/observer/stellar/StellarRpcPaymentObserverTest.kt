package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPathPaymentOperation
import org.stellar.anchor.ledger.PaymentTransferEvent
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.xdr.OperationType

class StellarRpcPaymentObserverTest {
  private lateinit var config: StellarPaymentObserverConfig
  private lateinit var paymentListeners: List<PaymentListener>
  private lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager
  private lateinit var paymentStreamerCursorStore: StellarPaymentStreamerCursorStore
  private lateinit var sacToAssetMapper: MockSacToAssetMapper
  private lateinit var observer: StellarRpcPaymentObserver

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
    paymentListeners = emptyList()
    paymentObservingAccountsManager = mockk(relaxed = true)
    paymentStreamerCursorStore = mockk(relaxed = true)
    sacToAssetMapper = MockSacToAssetMapper()
    observer =
      spyk(
        StellarRpcPaymentObserver(
          "https://soroban-testnet.stellar.org",
          config,
          paymentListeners,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
          sacToAssetMapper,
        ),
        recordPrivateCalls = true,
      )
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
