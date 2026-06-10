package org.stellar.anchor.platform.observer.stellar

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.platform.HealthCheckStatus.GREEN
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.ledger.StellarRpc
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.anchor.platform.observer.stellar.AbstractPaymentObserver.ObserverStatus
import org.stellar.sdk.Address
import org.stellar.sdk.KeyPair
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.requests.sorobanrpc.GetEventsRequest
import org.stellar.sdk.responses.sorobanrpc.GetEventsResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.SCMap
import org.stellar.sdk.xdr.SCMapEntry
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType

class StellarRpcObserverPoisonResilienceTest {
  private lateinit var config: StellarPaymentObserverConfig
  private lateinit var observer: StellarRpcPaymentObserver
  private lateinit var sorobanServer: SorobanServer
  private lateinit var stellarRpc: StellarRpc
  private lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager
  private lateinit var cursorStore: InMemoryCursorStore
  private lateinit var assetService: AssetService

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
    cursorStore = InMemoryCursorStore()
    assetService = mockk(relaxed = true)

    observer =
      spyk(
        StellarRpcPaymentObserver(
          stellarRpc,
          config,
          emptyList<PaymentListener>(),
          paymentObservingAccountsManager,
          cursorStore,
          MockSacToAssetMapper(),
          assetService,
        ),
        recordPrivateCalls = true,
      )
    every { observer.buildEventRequest(any()) } returns mockk<GetEventsRequest>()
  }

  @AfterEach
  fun tearDown() {
    runCatching { observer.shutdown() }
  }

  @Test
  fun `observer stays RUNNING after empty SCV_MAP poison event on real scheduler`() {
    val poisonValue =
      SCVal.builder()
        .discriminant(SCValType.SCV_MAP)
        .map(SCMap(emptyArray<SCMapEntry>()))
        .build()
        .toXdrBase64()

    setupSequentialResponses(poisonValue)
    observer.start()

    waitForCursor("NORMAL_CUR")

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("NORMAL_CUR", cursorStore.loadStellarRpcCursor())
  }

  @Test
  fun `observer stays RUNNING after one-entry SCV_MAP poison event on real scheduler`() {
    val poisonValue =
      SCVal.builder()
        .discriminant(SCValType.SCV_MAP)
        .map(
          SCMap(arrayOf(SCMapEntry(Scv.toSymbol("amount"), Scv.toInt128(BigInteger.valueOf(100)))))
        )
        .build()
        .toXdrBase64()

    setupSequentialResponses(poisonValue)
    observer.start()

    waitForCursor("NORMAL_CUR")

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("NORMAL_CUR", cursorStore.loadStellarRpcCursor())
  }

  @Test
  fun `observer stays RUNNING after SCV_MAP with wrong first-entry type on real scheduler`() {
    val poisonValue =
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

    setupSequentialResponses(poisonValue)
    observer.start()

    waitForCursor("NORMAL_CUR")

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("NORMAL_CUR", cursorStore.loadStellarRpcCursor())
  }

  @Test
  fun `observer stays RUNNING after contract-address recipient with SCV_U64 memo on real scheduler`() {
    val poisonValue =
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
    val contractAddress = Address.fromContract(ByteArray(32)).toSCVal()

    setupSequentialResponses(poisonValue, toSCVal = contractAddress)
    observer.start()

    waitForCursor("NORMAL_CUR")

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("NORMAL_CUR", cursorStore.loadStellarRpcCursor())
  }

  @Test
  fun `observer health check returns GREEN after processing poison events`() {
    val poisonValue =
      SCVal.builder()
        .discriminant(SCValType.SCV_MAP)
        .map(SCMap(emptyArray<SCMapEntry>()))
        .build()
        .toXdrBase64()

    setupSequentialResponses(poisonValue)
    observer.start()

    waitForCursor("NORMAL_CUR")

    val healthResult = observer.check()
    assertNotNull(healthResult)
    assertEquals(GREEN, healthResult.status)
  }

  @Test
  fun `observer advances cursor past mixed batch containing poison and valid events`() {
    val emptyMapPoison =
      SCVal.builder()
        .discriminant(SCValType.SCV_MAP)
        .map(SCMap(emptyArray<SCMapEntry>()))
        .build()
        .toXdrBase64()
    val validValue = Scv.toInt128(BigInteger.valueOf(100)).toXdrBase64()

    val callCount = AtomicInteger(0)
    every { sorobanServer.getEvents(any()) } answers
      {
        if (callCount.incrementAndGet() == 1)
          mockBatchResponse(listOf(emptyMapPoison, validValue), "BATCH_CUR")
        else mockEmptyBatchResponse("NORMAL_CUR")
      }

    observer.start()

    waitForCursor("NORMAL_CUR")

    assertEquals(ObserverStatus.RUNNING, observer.getStatus())
    assertEquals("NORMAL_CUR", cursorStore.loadStellarRpcCursor())
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private fun setupSequentialResponses(
    poisonValueBase64: String,
    toSCVal: SCVal = Scv.toAddress(KeyPair.random().accountId),
  ) {
    val callCount = AtomicInteger(0)
    every { sorobanServer.getEvents(any()) } answers
      {
        if (callCount.incrementAndGet() == 1)
          mockPoisonResponse(poisonValueBase64, toSCVal, "POISON_CUR")
        else mockEmptyBatchResponse("NORMAL_CUR")
      }
  }

  private fun mockPoisonResponse(
    valueBase64: String,
    toSCVal: SCVal,
    cursor: String,
  ): GetEventsResponse {
    val topics =
      listOf(
        Scv.toSymbol("transfer").toXdrBase64(),
        Scv.toAddress(KeyPair.random().accountId).toXdrBase64(),
        toSCVal.toXdrBase64(),
        Scv.toString("native").toXdrBase64(),
      )
    val event = mockk<GetEventsResponse.EventInfo>()
    every { event.topic } returns topics
    every { event.value } returns valueBase64

    return mockk<GetEventsResponse>().also {
      every { it.events } returns listOf(event)
      every { it.latestLedger } returns 100L
      every { it.cursor } returns cursor
    }
  }

  private fun mockBatchResponse(
    valuesBase64: List<String>,
    cursor: String,
  ): GetEventsResponse {
    val events =
      valuesBase64.map { valueBase64 ->
        val topics =
          listOf(
            Scv.toSymbol("transfer").toXdrBase64(),
            Scv.toAddress(KeyPair.random().accountId).toXdrBase64(),
            Scv.toAddress(KeyPair.random().accountId).toXdrBase64(),
            Scv.toString("native").toXdrBase64(),
          )
        mockk<GetEventsResponse.EventInfo>().also {
          every { it.topic } returns topics
          every { it.value } returns valueBase64
        }
      }
    return mockk<GetEventsResponse>().also {
      every { it.events } returns events
      every { it.latestLedger } returns 100L
      every { it.cursor } returns cursor
    }
  }

  private fun mockEmptyBatchResponse(cursor: String): GetEventsResponse =
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
  }
}

private class InMemoryCursorStore : StellarPaymentStreamerCursorStore {
  private var horizonCursor = ""
  private var stellarRpcCursor = ""

  override fun saveHorizonCursor(cursor: String) {
    horizonCursor = cursor
  }
  override fun loadHorizonCursor(): String = horizonCursor
  override fun saveStellarRpcCursor(cursor: String) {
    stellarRpcCursor = cursor
  }
  override fun loadStellarRpcCursor(): String = stellarRpcCursor
}
