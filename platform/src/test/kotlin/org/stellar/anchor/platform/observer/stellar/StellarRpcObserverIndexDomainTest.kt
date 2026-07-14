package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.PaymentTransferEvent
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.anchor.platform.observer.stellar.AbstractPaymentObserver.ObserverStatus
import org.stellar.sdk.Account
import org.stellar.sdk.Address
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.StrKey
import org.stellar.sdk.TOID
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.operations.InvokeHostFunctionOperation
import org.stellar.sdk.responses.sorobanrpc.Events
import org.stellar.sdk.responses.sorobanrpc.GetEventsResponse
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.ContractEvent
import org.stellar.sdk.xdr.ContractEventType
import org.stellar.sdk.xdr.ContractID
import org.stellar.sdk.xdr.ExtensionPoint
import org.stellar.sdk.xdr.Hash
import org.stellar.sdk.xdr.HostFunction
import org.stellar.sdk.xdr.HostFunctionType
import org.stellar.sdk.xdr.InvokeContractArgs
import org.stellar.sdk.xdr.InvokeHostFunctionResult
import org.stellar.sdk.xdr.InvokeHostFunctionResultCode
import org.stellar.sdk.xdr.OperationResult
import org.stellar.sdk.xdr.OperationResultCode
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.SCSymbol
import org.stellar.sdk.xdr.TransactionResult
import org.stellar.sdk.xdr.TransactionResultCode
import org.stellar.sdk.xdr.XdrString

class StellarRpcObserverIndexDomainTest {
  private lateinit var config: StellarPaymentObserverConfig
  private lateinit var observer: StellarRpcPaymentObserver
  private lateinit var sorobanServer: SorobanServer
  private lateinit var stellarRpc: StellarRpc
  private lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager
  private lateinit var cursorStore: LocalCursorStore
  private lateinit var assetService: AssetService
  private lateinit var listener: CaptureListener

  @BeforeEach
  fun setUp() {
    config =
      StellarPaymentObserverConfig().apply {
        silenceCheckInterval = 60
        silenceTimeout = 300
        silenceTimeoutRetries = 3
        initialStreamBackoffTime = 500
        maxStreamBackoffTime = 5000
        initialEventBackoffTime = 250
        maxEventBackoffTime = 2500
      }
    stellarRpc = mockk(relaxed = true)
    sorobanServer = mockk(relaxed = true)
    every { stellarRpc.sorobanServer } returns sorobanServer
    every { stellarRpc.getSorobanServer() } returns sorobanServer
    every { sorobanServer.getLatestLedger() } returns mockk { every { sequence } returns 10 }

    paymentObservingAccountsManager = mockk(relaxed = true)
    cursorStore = LocalCursorStore()
    assetService = mockk(relaxed = true)
    listener = CaptureListener()

    observer =
      StellarRpcPaymentObserver(
        stellarRpc,
        config,
        listOf(listener),
        paymentObservingAccountsManager,
        cursorStore,
        MockSacToAssetMapper(),
        assetService,
      )
  }

  @AfterEach
  fun tearDown() {
    runCatching { observer.shutdown() }
  }

  @Test
  fun `payment at operationIndex=1 in multi-op transaction is credited via real scheduler`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val amount = BigInteger.valueOf(5_000_000L)
    val txHash = "txHashMultiOpIntegration"
    val seqNum = 400L
    val appOrder = 1
    val paymentOpId = TOID(seqNum.toInt(), appOrder, 2).toInt64().toString()

    every { paymentObservingAccountsManager.lookupAndUpdate(toAccount) } returns true
    every { paymentObservingAccountsManager.lookupAndUpdate(fromAccount) } returns false

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

    val callCount = AtomicInteger(0)
    every { sorobanServer.getEvents(any()) } answers
      {
        if (callCount.incrementAndGet() == 1)
          mockEventBatch(
            listOf(Triple(fromAccount, toAccount, txHash to 1L)),
            listOf(amount),
            "MULTI_OP_CUR",
          )
        else emptyBatch("IDLE_CUR")
      }

    observer.start()
    waitForCursor("IDLE_CUR")

    val captured = listener.getByTo(toAccount)
    assertEquals(1, captured?.size)
    assertEquals(fromAccount, captured!![0].from)
    assertEquals(toAccount, captured[0].to)
    assertEquals(amount, captured[0].amount)
    assertEquals(paymentOpId, captured[0].operationId)
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
  }

  @Test
  fun `mixed batch credits both normal and multi-op events`() {
    val from1 = KeyPair.random().accountId
    val to1 = KeyPair.random().accountId
    val from2 = KeyPair.random().accountId
    val to2 = KeyPair.random().accountId

    val amount1 = BigInteger.valueOf(1_000_000L)
    val amount2 = BigInteger.valueOf(2_000_000L)

    val seqNum = 600L
    val appOrder = 1

    val txHash1 = "txHashNormalOp"
    val opId1 = TOID(seqNum.toInt(), appOrder, 1).toInt64().toString()

    val txHash2 = "txHashMultiOp2"
    val opId2 = TOID((seqNum + 1).toInt(), appOrder, 2).toInt64().toString()

    every { paymentObservingAccountsManager.lookupAndUpdate(to1) } returns true
    every { paymentObservingAccountsManager.lookupAndUpdate(from1) } returns false
    every { paymentObservingAccountsManager.lookupAndUpdate(to2) } returns true
    every { paymentObservingAccountsManager.lookupAndUpdate(from2) } returns false

    every { stellarRpc.getTransaction(txHash1) } returns
      LedgerTransaction.builder()
        .hash(txHash1)
        .sequenceNumber(seqNum)
        .applicationOrder(appOrder)
        .operations(
          listOf(
            LedgerOperation().apply {
              type = OperationType.PAYMENT
              paymentOperation =
                LedgerTransaction.LedgerPaymentOperation().apply {
                  from = from1
                  to = to1
                  asset = Asset.createNativeAsset().toXdr()
                  amount = amount1
                  id = opId1
                }
            }
          )
        )
        .build()

    every { stellarRpc.getTransaction(txHash2) } returns
      LedgerTransaction.builder()
        .hash(txHash2)
        .sequenceNumber(seqNum + 1)
        .applicationOrder(appOrder)
        .operations(
          listOf(
            LedgerOperation().apply {
              type = OperationType.PAYMENT
              paymentOperation =
                LedgerTransaction.LedgerPaymentOperation().apply {
                  from = from2
                  to = to2
                  asset = Asset.createNativeAsset().toXdr()
                  amount = amount2
                  id = opId2
                }
            }
          )
        )
        .build()

    val callCount = AtomicInteger(0)
    every { sorobanServer.getEvents(any()) } answers
      {
        if (callCount.incrementAndGet() == 1)
          mockEventBatch(
            listOf(
              Triple(from1, to1, txHash1 to 0L),
              Triple(from2, to2, txHash2 to 1L),
            ),
            listOf(amount1, amount2),
            "MIXED_CUR",
          )
        else emptyBatch("IDLE_CUR")
      }

    observer.start()
    waitForCursor("IDLE_CUR")

    val captured1 = listener.getByTo(to1)
    assertEquals(1, captured1?.size)
    assertEquals(amount1, captured1!![0].amount)
    assertEquals(opId1, captured1[0].operationId)

    val captured2 = listener.getByTo(to2)
    assertEquals(1, captured2?.size)
    assertEquals(amount2, captured2!![0].amount)
    assertEquals(opId2, captured2[0].operationId)
    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
  }

  @Test
  fun `sub-invocation transfer from a verified SAC is credited via real scheduler and real resolver wiring`() {
    val fromAccount = KeyPair.random()
    val toAccount = KeyPair.random().accountId
    val routerContractId = "CABZBKCMLL4U7ZYF2SJ2VIFZJQQR5LZPL6BH6W7PNSPVXVUT5VMQ27DR"
    val sacContractId = "CAOVNOZGTFE7HD64O3HYG2TFJBUUPVHAGINDZLTKTS6P2TY6LZWZGIWS"
    val amount = BigInteger.valueOf(750_000L)
    val seqNum = 900L
    val appOrder = 1
    val opId = TOID(seqNum.toInt(), appOrder, 1).toInt64().toString()
    val txHash = "txHashSubInvocationRealWiring"

    val realStellarRpc = StellarRpc("https://fake-rpc.invalid")
    val mockSorobanServer = mockk<SorobanServer>(relaxed = true)
    val sorobanServerField = StellarRpc::class.java.getDeclaredField("sorobanServer")
    sorobanServerField.isAccessible = true
    sorobanServerField.set(realStellarRpc, mockSorobanServer)
    val nativeAsset = Asset.createNativeAsset().toXdr()
    realStellarRpc.setSacResolver { id -> if (id == sacContractId) nativeAsset else null }
    every { mockSorobanServer.getLatestLedger() } returns mockk { every { sequence } returns 10 }

    // Top-level operation invokes a router contract's own function (not "transfer"), so
    // convert() drops it; the genuine transfer only shows up as a sub-invocation contract event.
    val hostFunction =
      HostFunction.builder()
        .discriminant(HostFunctionType.HOST_FUNCTION_TYPE_INVOKE_CONTRACT)
        .invokeContract(
          InvokeContractArgs.builder()
            .contractAddress(Address(routerContractId).toSCAddress())
            .functionName(SCSymbol(XdrString("swap")))
            .args(arrayOf())
            .build()
        )
        .build()
    val invokeOp = InvokeHostFunctionOperation.builder().hostFunction(hostFunction).build()
    val account = Account(fromAccount.accountId, 1L)
    val network = Network("Test SDF Network ; September 2015")
    val tx =
      TransactionBuilder(account, network)
        .addOperation(invokeOp)
        .setBaseFee(100)
        .setTimeout(300)
        .build()
    tx.sign(fromAccount)
    val envelopeXdr = tx.toEnvelopeXdrBase64()

    val invokeResult =
      OperationResult.builder()
        .discriminant(OperationResultCode.opINNER)
        .tr(
          OperationResult.OperationResultTr.builder()
            .discriminant(OperationType.INVOKE_HOST_FUNCTION)
            .invokeHostFunctionResult(
              InvokeHostFunctionResult.builder()
                .discriminant(InvokeHostFunctionResultCode.INVOKE_HOST_FUNCTION_SUCCESS)
                .success(Hash(ByteArray(32)))
                .build()
            )
            .build()
        )
        .build()
    val resultXdr =
      TransactionResult.builder()
        .feeCharged(org.stellar.sdk.xdr.Int64(100L))
        .result(
          TransactionResult.TransactionResultResult.builder()
            .discriminant(TransactionResultCode.txSUCCESS)
            .results(arrayOf(invokeResult))
            .build()
        )
        .ext(TransactionResult.TransactionResultExt.builder().discriminant(0).build())
        .build()
        .toXdrBase64()

    val transferEventXdr =
      ContractEvent.builder()
        .ext(ExtensionPoint.builder().discriminant(0).build())
        .contractID(ContractID(Hash(StrKey.decodeContract(sacContractId))))
        .type(ContractEventType.CONTRACT)
        .body(
          ContractEvent.ContractEventBody.builder()
            .discriminant(0)
            .v0(
              ContractEvent.ContractEventBody.ContractEventV0.builder()
                .topics(
                  arrayOf(
                    Scv.toSymbol("transfer"),
                    Scv.toAddress(fromAccount.accountId),
                    Scv.toAddress(toAccount),
                    Scv.toString("native"),
                  )
                )
                .data(Scv.toInt128(amount))
                .build()
            )
            .build()
        )
        .build()
        .toXdrBase64()

    val txnResponse =
      GetTransactionResponse(
        GetTransactionResponse.GetTransactionStatus.SUCCESS,
        txHash,
        1000L,
        0L,
        1L,
        0L,
        appOrder,
        false,
        envelopeXdr,
        resultXdr,
        null,
        seqNum,
        1_700_000_000L,
        null,
        Events(null, listOf(listOf(transferEventXdr))),
      )
    every { mockSorobanServer.getTransaction(txHash) } returns txnResponse

    every { paymentObservingAccountsManager.lookupAndUpdate(toAccount) } returns true
    every { paymentObservingAccountsManager.lookupAndUpdate(fromAccount.accountId) } returns false

    val callCount = AtomicInteger(0)
    every { mockSorobanServer.getEvents(any()) } answers
      {
        if (callCount.incrementAndGet() == 1)
          mockEventBatch(
            listOf(Triple(fromAccount.accountId, toAccount, txHash to 0L)),
            listOf(amount),
            "SUBINV_CUR",
          )
        else emptyBatch("SUBINV_IDLE_CUR")
      }

    val localListener = CaptureListener()
    val localCursorStore = LocalCursorStore()
    val realObserver =
      StellarRpcPaymentObserver(
        realStellarRpc,
        config,
        listOf(localListener),
        paymentObservingAccountsManager,
        localCursorStore,
        MockSacToAssetMapper(),
        assetService,
      )

    try {
      realObserver.start()
      val deadline = System.currentTimeMillis() + 6000
      while (
        System.currentTimeMillis() < deadline &&
          localCursorStore.loadStellarRpcCursor() != "SUBINV_IDLE_CUR"
      ) {
        Thread.sleep(100)
      }
      assertEquals("SUBINV_IDLE_CUR", localCursorStore.loadStellarRpcCursor())

      val captured = localListener.getByTo(toAccount)
      assertEquals(1, captured?.size)
      assertEquals(fromAccount.accountId, captured!![0].from)
      assertEquals(toAccount, captured[0].to)
      assertEquals(amount, captured[0].amount)
      assertEquals(opId, captured[0].operationId)
      assertEquals(ObserverStatus.RUNNING, realObserver.getStatus())
    } finally {
      runCatching { realObserver.shutdown() }
    }
  }

  private fun mockEventBatch(
    transfers: List<Triple<String, String, Pair<String, Long>>>,
    amounts: List<BigInteger>,
    cursor: String,
  ): GetEventsResponse {
    val events =
      transfers.mapIndexed { i, (from, to, hashAndIndex) ->
        val (txHash, operationIndex) = hashAndIndex
        val topics =
          listOf(
            Scv.toSymbol("transfer").toXdrBase64(),
            Scv.toAddress(from).toXdrBase64(),
            Scv.toAddress(to).toXdrBase64(),
            Scv.toString("native").toXdrBase64(),
          )
        mockk<GetEventsResponse.EventInfo>(relaxed = true).also {
          every { it.topic } returns topics
          every { it.value } returns Scv.toInt128(amounts[i]).toXdrBase64()
          every { it.transactionHash } returns txHash
          every { it.operationIndex } returns operationIndex
        }
      }
    return mockk<GetEventsResponse>().also {
      every { it.events } returns events
      every { it.latestLedger } returns 100L
      every { it.cursor } returns cursor
    }
  }

  private fun emptyBatch(cursor: String): GetEventsResponse =
    mockk<GetEventsResponse>().also {
      every { it.events } returns emptyList()
      every { it.latestLedger } returns 101L
      every { it.cursor } returns cursor
    }

  private fun waitForCursor(expected: String, timeoutMs: Long = 6000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (cursorStore.loadStellarRpcCursor() == expected) return
      Thread.sleep(100)
    }
    throw AssertionError(
      "Timed out waiting for cursor '$expected' (last='${cursorStore.loadStellarRpcCursor()}')"
    )
  }
}

private class LocalCursorStore : StellarPaymentStreamerCursorStore {
  @Volatile private var stellarRpcCursor = ""

  override fun saveHorizonCursor(cursor: String) {}

  override fun loadHorizonCursor(): String = ""

  override fun saveStellarRpcCursor(cursor: String) {
    stellarRpcCursor = cursor
  }

  override fun loadStellarRpcCursor(): String = stellarRpcCursor
}

private class CaptureListener : PaymentListener {
  private val byTo = mutableMapOf<String, MutableList<PaymentTransferEvent>>()
  private val lock = Any()

  override fun onReceived(event: PaymentTransferEvent?) {
    event ?: return
    synchronized(lock) { byTo.getOrPut(event.to) { CopyOnWriteArrayList() }.add(event) }
  }

  fun getByTo(to: String): List<PaymentTransferEvent>? = synchronized(lock) { byTo[to]?.toList() }
}
