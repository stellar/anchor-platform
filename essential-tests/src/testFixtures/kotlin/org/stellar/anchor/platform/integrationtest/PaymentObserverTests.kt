package org.stellar.anchor.platform.integrationtest

import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.stellar.anchor.ledger.PaymentTransferEvent
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.anchor.platform.observer.stellar.HorizonPaymentObserver
import org.stellar.anchor.platform.observer.stellar.PaymentObservingAccountsManager
import org.stellar.anchor.platform.observer.stellar.StellarPaymentStreamerCursorStore
import org.stellar.anchor.platform.observer.stellar.StellarRpcPaymentObserver
import org.stellar.anchor.util.AssetHelper
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Log.info
import org.stellar.sdk.*
import org.stellar.sdk.operations.PathPaymentStrictSendOperation
import org.stellar.sdk.operations.PaymentOperation

class PaymentObserverTests {
  companion object {
    private val paymentObservingAccountManager =
      mockk<PaymentObservingAccountsManager>(relaxed = true)
    private lateinit var fromKeyPair: KeyPair
    private lateinit var toKeyPair: KeyPair
    private val keyMap = mutableListOf<String>()
    private val eventCaptureListenerHorizon = EventCapturingListener()
    private val eventCaptureListenerStellarRpc = EventCapturingListener()
    private lateinit var horizonPaymentObserver: HorizonPaymentObserver
    private lateinit var stellarRpcPaymentObserver: StellarRpcPaymentObserver
    private val stellarRpc = SorobanServer("https://soroban-testnet.stellar.org")

    @JvmStatic
    @BeforeAll
    fun start() {
      val keyPairs = createAccounts()
      fromKeyPair = keyPairs.first
      toKeyPair = keyPairs.second

      keyMap.add(fromKeyPair.accountId)
      keyMap.add(toKeyPair.accountId)

      every { paymentObservingAccountManager.lookupAndUpdate(any()) } answers
        {
          keyMap.contains(firstArg<String>())
        }

      horizonPaymentObserver =
        HorizonPaymentObserver(
          "https://horizon-testnet.stellar.org",
          StellarPaymentObserverConfig(5, 90, 5, 5, 300, 5, 300),
          listOf(eventCaptureListenerHorizon),
          paymentObservingAccountManager,
          TestCursorStore(),
        )
      horizonPaymentObserver.start()

      stellarRpcPaymentObserver =
        StellarRpcPaymentObserver(
          "https://soroban-testnet.stellar.org",
          StellarPaymentObserverConfig(5, 90, 5, 5, 300, 5, 300),
          listOf(eventCaptureListenerStellarRpc),
          paymentObservingAccountManager,
          TestCursorStore(),
        )
      stellarRpcPaymentObserver.start()
    }

    @JvmStatic
    @AfterAll
    fun shutdown() {
      horizonPaymentObserver.shutdown()
      stellarRpcPaymentObserver.shutdown()
    }
  }

  @AfterEach
  fun cleanup() {
    eventCaptureListenerHorizon.reset()
    eventCaptureListenerStellarRpc.reset()
  }

  @Test
  fun `submit a payment and assert events are received as expected`() = runBlocking {
    val txn = sendTestPayment(fromKeyPair, toKeyPair)
    waitForEventsCoroutine(fromKeyPair.accountId, eventCaptureListenerHorizon)
    assertEventsPayment(
      txn,
      eventCaptureListenerHorizon.getEventByFrom(fromKeyPair.accountId),
      eventCaptureListenerHorizon.getEventByTo(toKeyPair.accountId),
    )

    waitForEventsCoroutine(fromKeyPair.accountId, eventCaptureListenerStellarRpc)
    assertEventsPayment(
      txn,
      eventCaptureListenerStellarRpc.getEventByFrom(fromKeyPair.accountId),
      eventCaptureListenerStellarRpc.getEventByTo(toKeyPair.accountId),
    )
  }

  @Test
  fun `submit a path payment and assert events are received as expected`() = runBlocking {
    val txn = sendTestPathPayment(fromKeyPair, toKeyPair)
    waitForEventsCoroutine(fromKeyPair.accountId, eventCaptureListenerHorizon)

    assertEventsPathPayment(
      txn,
      eventCaptureListenerHorizon.getEventByFrom(fromKeyPair.accountId),
      eventCaptureListenerHorizon.getEventByTo(toKeyPair.accountId),
    )

    waitForEventsCoroutine(fromKeyPair.accountId, eventCaptureListenerStellarRpc)
    assertEventsPathPayment(
      txn,
      eventCaptureListenerStellarRpc.getEventByFrom(fromKeyPair.accountId),
      eventCaptureListenerStellarRpc.getEventByTo(toKeyPair.accountId),
    )
  }

  private fun assertEventsPayment(
    txn: Transaction,
    fromEvent: List<PaymentTransferEvent>?,
    toEvent: List<PaymentTransferEvent>?,
  ) {
    assertEquals(1, fromEvent?.size)
    assertEquals(1, toEvent?.size)
    assertEquals(fromKeyPair.accountId, fromEvent!![0].from)
    assertEquals(toKeyPair.accountId, fromEvent[0].to)
    val paymentOperation: PaymentOperation = txn.operations[0] as PaymentOperation
    assertEquals(AssetHelper.toXdrAmount(paymentOperation.amount.toString()), fromEvent[0].amount)
    assertEquals(
      AssetHelper.getSep11AssetName(paymentOperation.asset.toXdr()),
      fromEvent[0].sep11Asset,
    )
    assertEquals(fromEvent, toEvent)
  }

  private fun assertEventsPathPayment(
    txn: Transaction,
    fromEvent: List<PaymentTransferEvent>?,
    toEvent: List<PaymentTransferEvent>?,
  ) {
    assertEquals(1, fromEvent?.size)
    assertEquals(1, toEvent?.size)
    assertEquals(fromKeyPair.accountId, fromEvent!![0].from)
    assertEquals(toKeyPair.accountId, fromEvent[0].to)
    val paymentOperation: PathPaymentStrictSendOperation =
      txn.operations[0] as PathPaymentStrictSendOperation
    assertEquals(
      AssetHelper.toXdrAmount(paymentOperation.sendAmount.toString()),
      fromEvent[0].amount,
    )
    assertEquals(
      AssetHelper.getSep11AssetName(paymentOperation.destAsset.toXdr()),
      fromEvent[0].sep11Asset,
    )
    assertEquals(fromEvent, toEvent)
  }

  private suspend fun waitForEventsCoroutine(
    fromAccountId: String,
    listener: EventCapturingListener,
    timeout: Long = 10000L,
  ) {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime <= timeout) {
      val events = listener.getEventByFrom(fromAccountId)
      if (events != null) {
        info("Event captured for account: $fromAccountId")
        return
      }
      delay(1000)
    }
    info("Timeout waiting for event for account: $fromAccountId")
  }

  private fun sendTestPayment(fromKeyPair: KeyPair, toKeyPair: KeyPair): Transaction {
    info("Sending payment from ${fromKeyPair.accountId} to ${toKeyPair.accountId}")
    val fromAccount: TransactionBuilderAccount = stellarRpc.getAccount(fromKeyPair.accountId)

    val txn =
      TransactionBuilder(fromAccount, Network.TESTNET)
        .setBaseFee(100)
        .addOperation(
          PaymentOperation.builder()
            .sourceAccount(fromKeyPair.accountId)
            .destination(toKeyPair.accountId)
            .asset(Asset.createNativeAsset())
            .amount(BigDecimal(1))
            .build()
        )
        .setTimeout(100)
        .build()
    txn.sign(fromKeyPair)
    stellarRpc.sendTransaction(txn)
    return txn
  }

  private fun sendTestPathPayment(fromKeyPair: KeyPair, toKeyPair: KeyPair): Transaction {
    println("Sending payment from ${fromKeyPair.accountId} to ${toKeyPair.accountId}")
    val fromAccount: TransactionBuilderAccount = stellarRpc.getAccount(fromKeyPair.accountId)

    val txn =
      TransactionBuilder(fromAccount, Network.TESTNET)
        .setBaseFee(100)
        .addOperation(
          PathPaymentStrictSendOperation.builder()
            .sourceAccount(fromKeyPair.accountId)
            .destination(toKeyPair.accountId)
            .path(arrayOf(Asset.createNativeAsset()))
            .sendAsset(Asset.createNativeAsset())
            .destAsset(Asset.createNativeAsset())
            .destMin(BigDecimal(1))
            .sendAmount(BigDecimal(1))
            .build()
        )
        .setTimeout(100)
        .build()
    txn.sign(fromKeyPair)
    stellarRpc.sendTransaction(txn)
    return txn
  }
}

internal class TestCursorStore : StellarPaymentStreamerCursorStore {
  private var cursor: String = ""

  override fun save(cursor: String) {
    this.cursor = cursor
  }

  override fun load(): String {
    return cursor
  }
}

internal class EventCapturingListener : PaymentListener {
  private val eventsByFrom = mutableMapOf<String, MutableList<PaymentTransferEvent>>()
  private val eventsByTo = mutableMapOf<String, MutableList<PaymentTransferEvent>>()

  override fun onReceived(paymentTransferEvent: PaymentTransferEvent?) {
    info("Received payment transfer event: ${GsonUtils.getInstance().toJson(paymentTransferEvent)}")
    if (eventsByFrom[paymentTransferEvent!!.from] == null) {
      eventsByFrom[paymentTransferEvent.from] = mutableListOf(paymentTransferEvent)
    } else {
      eventsByFrom[paymentTransferEvent.from]?.add(paymentTransferEvent)
    }

    if (eventsByTo[paymentTransferEvent.to] == null) {
      eventsByTo[paymentTransferEvent.to] = mutableListOf(paymentTransferEvent)
    } else {
      eventsByTo[paymentTransferEvent.to]?.add(paymentTransferEvent)
    }
  }

  fun getEventByFrom(from: String): List<PaymentTransferEvent>? {
    return eventsByFrom[from]
  }

  fun getEventByTo(to: String): List<PaymentTransferEvent>? {
    return eventsByTo[to]
  }

  fun reset() {
    eventsByFrom.clear()
    eventsByTo.clear()
  }
}

internal fun createAccounts(): Pair<KeyPair, KeyPair> {
  // Generate two random keypairs
  val fromKeyPair = KeyPair.random()
  val toKeyPair = KeyPair.random()

  fundAccount(fromKeyPair)
  fundAccount(toKeyPair)

  return Pair(fromKeyPair, toKeyPair)
}

fun fundAccount(keyPair: KeyPair) {
  val friendBotUrl = "https://friendbot.stellar.org/?addr=${keyPair.accountId}"
  try {
    java.net.URL(friendBotUrl).openStream()
    info("Funded account: ${keyPair.accountId}")
  } catch (e: java.io.IOException) {
    info("ERROR! " + e.message)
    throw e
  }
}
