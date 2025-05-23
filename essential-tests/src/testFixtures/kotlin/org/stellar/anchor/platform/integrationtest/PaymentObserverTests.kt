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
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.exception.PrepareTransactionException
import org.stellar.sdk.operations.InvokeHostFunctionOperation
import org.stellar.sdk.operations.PathPaymentStrictSendOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse
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
    private lateinit var walletContractId: String
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
      // the wasmId is the wasm hash of the contract under soroban/contracts/account.
      val wasmId = "a4f2bbf00e661546a2db6de1922dc638ee94e0b52c48adb051dad42329e866fb"
      walletContractId =
        createContractWithWasmIdAndGetContractId(
          stellarRpc,
          Network.TESTNET,
          wasmId,
          fromKeyPair,
          listOf(Scv.toAddress(fromKeyPair.accountId), Scv.toBytes(fromKeyPair.publicKey)),
        )

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
  fun `submit a payment to a contract, send the payment back, and assert events are received as expected`():
    Unit = runBlocking {
    // Send a payment from the classic account to the contract account
    sendWithStellarAssetContract(
      rpc = stellarRpcPaymentObserver.sorobanServer,
      network = Network.TESTNET,
      signer = fromKeyPair,
      from = fromKeyPair.accountId,
      to = walletContractId,
      asset = Asset.createNativeAsset(),
      amount = "0.0000002",
    )

    // Wait for the events to be captured
    waitForEventsCoroutine(fromKeyPair.accountId, eventCaptureListenerStellarRpc)

    // Assert that the events are received as expected
    val fromEvent = eventCaptureListenerStellarRpc.getEventByFrom(fromKeyPair.accountId)
    val toEvent = eventCaptureListenerStellarRpc.getEventByTo(walletContractId)
    assertEquals(1, fromEvent?.size)
    assertEquals(1, toEvent?.size)
    assertEquals(fromKeyPair.accountId, fromEvent!![0].from)
    assertEquals(walletContractId, toEvent!![0].to)
    assertEquals(BigInteger.valueOf(AssetHelper.toXdrAmount("0.0000002")), fromEvent[0].amount)
    assertEquals(Asset.createNativeAsset().toString(), fromEvent[0].sep11Asset)
    assertEquals(fromEvent, toEvent)

    // Send a payment from the contract account back to the classic account
    sendWithStellarAssetContract(
      rpc = stellarRpcPaymentObserver.sorobanServer,
      network = Network.TESTNET,
      signer = fromKeyPair,
      from = walletContractId,
      to = fromKeyPair.accountId,
      asset = Asset.createNativeAsset(),
      amount = "0.0000001",
    )

    // Wait for the events to be captured
    waitForEventsCoroutine(walletContractId, eventCaptureListenerStellarRpc)

    // Assert that the events are received as expected
    val fromEvent2 = eventCaptureListenerStellarRpc.getEventByFrom(walletContractId)
    val toEvent2 = eventCaptureListenerStellarRpc.getEventByTo(fromKeyPair.accountId)
    assertEquals(1, fromEvent2?.size)
    assertEquals(1, toEvent2?.size)
    assertEquals(walletContractId, fromEvent2!![0].from)
    assertEquals(fromKeyPair.accountId, toEvent2!![0].to)
    assertEquals(BigInteger.valueOf(AssetHelper.toXdrAmount("0.0000001")), fromEvent2[0].amount)
    assertEquals(fromEvent2, toEvent2)
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
        .setBaseFee(MIN_BASE_FEE)
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
    assertEquals(
      BigInteger.valueOf(AssetHelper.toXdrAmount(paymentOperation.amount.toString())),
      fromEvent[0].amount
    )
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
      BigInteger.valueOf(AssetHelper.toXdrAmount(paymentOperation.sendAmount.toString())),
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

internal fun createContractWithWasmIdAndGetContractId(
  sorobanServer: SorobanServer,
  network: Network,
  wasmId: String,
  sourceAccount: KeyPair,
  constructorArgs: List<SCVal>,
): String {
  val txHash: String? =
    createContractWithWasmId(sorobanServer, network, wasmId, sourceAccount, constructorArgs)

  // Wait until the transaction is created
  var getTransactionResponse: GetTransactionResponse
  // Check the transaction status
  while (true) {
    getTransactionResponse = sorobanServer.getTransaction(txHash)
    if (GetTransactionResponse.GetTransactionStatus.NOT_FOUND != getTransactionResponse.status) {
      break
    }
    // Wait for 3 seconds before checking the transaction status again
    Thread.sleep(3000)
  }
  return StrKey.encodeContract(
    getTransactionResponse.parseResultMetaXdr().v3.sorobanMeta.returnValue.address.contractId.hash
  )
}

internal fun createContractWithWasmId(
  sorobanServer: SorobanServer,
  network: Network,
  wasmId: String,
  sourceAccount: KeyPair,
  constructorArgs: List<SCVal>,
): String {
  val source = sorobanServer.getAccount(sourceAccount.getAccountId())

  val invokeHostFunctionOperation: InvokeHostFunctionOperation =
    InvokeHostFunctionOperation.createContractOperationBuilder(
        wasmId,
        Address(sourceAccount.getAccountId()),
        constructorArgs,
        null,
      )
      .build()

  // Build the transaction
  val unpreparedTransaction =
    TransactionBuilder(source, network)
      .setBaseFee(MIN_BASE_FEE)
      .addOperation(invokeHostFunctionOperation)
      .setTimeout(300)
      .build()

  // Prepare the transaction
  val transaction: Transaction
  try {
    transaction = sorobanServer.prepareTransaction(unpreparedTransaction)
  } catch (e: PrepareTransactionException) {
    throw RuntimeException("Prepare transaction failed", e)
  } catch (e: NetworkException) {
    throw RuntimeException("Network error", e)
  }

  // Sign the transaction
  transaction.sign(sourceAccount)

  // Send the transaction
  val sendTransactionResponse: SendTransactionResponse
  try {
    sendTransactionResponse = sorobanServer.sendTransaction(transaction)
  } catch (e: NetworkException) {
    throw RuntimeException("Send transaction failed", e)
  }
  if (
    SendTransactionResponse.SendTransactionStatus.PENDING != sendTransactionResponse.getStatus()
  ) {
    throw RuntimeException("Send transaction failed: " + sendTransactionResponse)
  }

  return sendTransactionResponse.getHash()
}
