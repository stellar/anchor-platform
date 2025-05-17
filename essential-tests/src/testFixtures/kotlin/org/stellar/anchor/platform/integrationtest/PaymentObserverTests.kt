package org.stellar.anchor.platform.integrationtest

import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import org.stellar.sdk.AbstractTransaction.MIN_BASE_FEE
import org.stellar.sdk.Auth.authorizeEntry
import org.stellar.sdk.operations.InvokeHostFunctionOperation
import org.stellar.sdk.operations.PathPaymentStrictSendOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType
import org.stellar.sdk.xdr.SorobanAuthorizationEntry

class PaymentObserverTests {
  companion object {
    private val paymentObservingAccountManager =
      mockk<PaymentObservingAccountsManager>(relaxed = true)
    private lateinit var fromKeyPair: KeyPair
    private lateinit var toKeyPair: KeyPair
    private lateinit var fromKeyPair2: KeyPair
    private lateinit var toKeyPair2: KeyPair
    private val keyMap = mutableListOf<String>()
    private val eventCaptureListenerHorizon = EventCapturingListener()
    private val eventCaptureListenerStellarRpc = EventCapturingListener()
    private lateinit var horizonPaymentObserver: HorizonPaymentObserver
    private lateinit var stellarRpcPaymentObserver: StellarRpcPaymentObserver
    private val stellarRpc = SorobanServer("https://soroban-testnet.stellar.org")

    @JvmStatic
    @BeforeAll
    fun start() {
      fromKeyPair = createAndFundAccount()
      toKeyPair = createAndFundAccount()
      fromKeyPair2 = createAndFundAccount()
      toKeyPair2 = createAndFundAccount()

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
  fun `submit a payment to a contract and assert events are received as expected`(): Unit =
    runBlocking {
      val smartWalletId = "CAYXY6QGTPOCZ676MLGT5JFESVROJ6OJF7VW3LLXMTC2RQIZTP5JYNEL"
      sendWithStellarAssetContract(
        rpc = stellarRpcPaymentObserver.sorobanServer,
        network = Network.TESTNET,
        signer = fromKeyPair,
        from = fromKeyPair.accountId,
        to = smartWalletId,
        asset = Asset.createNativeAsset(),
        amount = "0.0000001",
      )
      waitForEventsCoroutine(fromKeyPair.accountId, eventCaptureListenerStellarRpc)

      val fromEvent = eventCaptureListenerStellarRpc.getEventByFrom(fromKeyPair.accountId)
      val toEvent = eventCaptureListenerStellarRpc.getEventByTo(smartWalletId)
      assertEquals(1, fromEvent?.size)
      assertEquals(1, toEvent?.size)
      assertEquals(fromKeyPair.accountId, fromEvent!![0].from)
      assertEquals(smartWalletId, toEvent!![0].to)
      assertEquals(AssetHelper.toXdrAmount("0.0000001"), fromEvent[0].amount)
      assertEquals(Asset.createNativeAsset().toString(), fromEvent[0].sep11Asset)
      assertEquals(fromEvent, toEvent)
    }

  @Test
  fun `submit a payment and assert events are received as expected`() = runBlocking {
    // send unmonitored payment
    sendTestPayment(fromKeyPair2, toKeyPair2)
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

    assertNull(eventCaptureListenerStellarRpc.getEventByFrom(fromKeyPair2.accountId))
    assertNull(eventCaptureListenerStellarRpc.getEventByTo(toKeyPair2.accountId))
  }

  @Test
  fun `submit a path payment and assert events are received as expected`() = runBlocking {
    // send unmonitored path payment
    sendTestPathPayment(fromKeyPair2, toKeyPair2)
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

    assertNull(eventCaptureListenerStellarRpc.getEventByFrom(fromKeyPair2.accountId))
    assertNull(eventCaptureListenerStellarRpc.getEventByTo(toKeyPair2.accountId))
  }

  // TODO: Merge with WalletClient of the feature/c-account branch
  private fun sendWithStellarAssetContract(
    rpc: SorobanServer,
    network: Network,
    signer: KeyPair,
    from: String,
    to: String,
    asset: Asset,
    amount: String,
  ): SendTransactionResponse? {
    val parameters =
      mutableListOf(
        // from=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(from).address)
          .build(),
        // to=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(to).address)
          .build(),
        // amount=
        SCVal.builder()
          .discriminant(SCValType.SCV_I128)
          .i128(Scv.toInt128(BigInteger.valueOf((amount.toFloat() * 10000000).toLong())).i128)
          .build(),
      )

    val operationBuilder =
      InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
          asset.getContractId(network),
          "transfer",
          parameters,
        )
        .sourceAccount(signer.accountId)
    var account = rpc.getAccount(signer.accountId)
    val transaction =
      TransactionBuilder(account, network)
        .addOperation(operationBuilder.build())
        .setBaseFee(MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    // Sign authorization entries if needed
    val simulationResponse = rpc.simulateTransaction(transaction)
    val signedAuthEntries = mutableListOf<SorobanAuthorizationEntry>()
    simulationResponse.results.forEach {
      it.auth.forEach { entryXdr ->
        val entry = SorobanAuthorizationEntry.fromXdrBase64(entryXdr)
        val validUntilLedgerSeq = simulationResponse.latestLedger + 10

        val signedEntry = authorizeEntry(entry, signer, validUntilLedgerSeq, network)
        signedAuthEntries.add(signedEntry)
      }
    }

    // Rebuild the operation with the signed authorization entries
    if (signedAuthEntries.isNotEmpty()) {
      operationBuilder.auth(signedAuthEntries)
    }

    account = rpc.getAccount(signer.accountId)
    val authorizedTransaction =
      TransactionBuilder(account, network)
        .addOperation(operationBuilder.build())
        .setBaseFee(Transaction.MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    // sign and send the transaction
    val preparedTransaction = rpc.prepareTransaction(authorizedTransaction)
    preparedTransaction.sign(signer)
    return rpc.sendTransaction(preparedTransaction)
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

internal fun createAndFundAccount(): KeyPair {
  val keyPair = KeyPair.random()
  val friendBotUrl = "https://friendbot.stellar.org/?addr=${keyPair.accountId}"
  try {
    java.net.URL(friendBotUrl).openStream()
    info("Funded account: ${keyPair.accountId}")
  } catch (e: java.io.IOException) {
    info("ERROR! " + e.message)
    throw e
  }

  return keyPair
}
