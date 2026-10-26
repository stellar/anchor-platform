@file:Suppress("unused")

package org.stellar.anchor.platform.observer.stellar

import com.google.gson.reflect.TypeToken
import io.mockk.*
import io.mockk.impl.annotations.MockK
import javax.net.ssl.SSLProtocolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.CannotCreateTransactionException
import org.stellar.anchor.api.platform.HealthCheckStatus.RED
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.platform.config.PaymentObserverConfig.StellarPaymentObserverConfig
import org.stellar.anchor.platform.observer.PaymentListener
import org.stellar.anchor.platform.observer.stellar.AbstractPaymentObserver.ObserverStatus
import org.stellar.sdk.Server
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.requests.SSEStream
import org.stellar.sdk.responses.Page
import org.stellar.sdk.responses.gson.GsonSingleton
import org.stellar.sdk.responses.operations.OperationResponse

class StellarPaymentObserverTest {
  companion object {
    const val TEST_HORIZON_URI = "https://horizon-testnet.stellar.org/"
  }

  @MockK lateinit var paymentStreamerCursorStore: StellarPaymentStreamerCursorStore
  @MockK lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager
  @MockK lateinit var paymentListener: PaymentListener

  private val stellarPaymentObserverConfig = StellarPaymentObserverConfig(1, 5, 1, 1, 2, 1, 2)

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxed = true)
  }

  @Test
  fun `test if StellarPaymentObserver will fetch the cursor from the DB, then fallback to the Network`() {
    // 1 - If there is a stored cursor, we'll use that.
    every { paymentStreamerCursorStore.loadHorizonCursor() } returns "123"
    var stellarObserver =
      spyk(
        HorizonPaymentObserver(
          Horizon(TEST_HORIZON_URI),
          stellarPaymentObserverConfig,
          null,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )

    every { stellarObserver.fetchLatestCursorFromHorizon() } returns "1000"
    var gotCursor = stellarObserver.fetchStreamingCursor()
    assertEquals("123", gotCursor)
    verify(exactly = 1) { paymentStreamerCursorStore.loadHorizonCursor() }

    // 2 - If there is no stored constructor, we will fall back to fetching a result from the
    // network.
    every { paymentStreamerCursorStore.loadHorizonCursor() } returns null
    mockkConstructor(Server::class)
    stellarObserver =
      HorizonPaymentObserver(
        Horizon(TEST_HORIZON_URI),
        stellarPaymentObserverConfig,
        null,
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
      )

    // 2.1 If fetching from the network throws an error, we throw instead of returning `null`
    // (HZCUR-01)
    every {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    } throws NetworkException(null, "Some IO Problem happened!")

    assertThrows<RuntimeException> { stellarObserver.fetchStreamingCursor() }
    verify(exactly = 2) { paymentStreamerCursorStore.loadHorizonCursor() }
    verify(exactly = 1) {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    }

    // 2.2 If fetching from the network does not return any result, the network has no payments
    // yet, so we stream from the tip (HZCUR-04)
    every {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    } returns null

    gotCursor = stellarObserver.fetchStreamingCursor()
    verify(exactly = 3) { paymentStreamerCursorStore.loadHorizonCursor() }
    verify(exactly = 2) {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    }
    assertEquals("now", gotCursor)

    // 2.3 If fetching from the network returns a value, use that.
    val opPageJson =
      """{
      "_embedded": {
        "records": [
          {
            "paging_token": "4322708489777153",
            "type_i": 0
          }
        ]
      }
    }"""
    val operationPageType = object : TypeToken<Page<OperationResponse?>?>() {}.type
    val operationPage: Page<OperationResponse> =
      GsonSingleton.getInstance().fromJson(opPageJson, operationPageType)

    every {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    } returns operationPage

    gotCursor = stellarObserver.fetchStreamingCursor()
    verify(exactly = 4) { paymentStreamerCursorStore.loadHorizonCursor() }
    verify(exactly = 3) {
      constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
        .payments()
        .order(RequestBuilder.Order.DESC)
        .limit(1)
        .execute()
    }
    assertEquals("4322708489777153", gotCursor)
  }

  @Test
  fun `test if SSEStream exception will leave the observer in STREAM_ERROR state`() {
    val stream: SSEStream<OperationResponse> = mockk(relaxed = true)
    val observer =
      spyk(
        HorizonPaymentObserver(
          Horizon(TEST_HORIZON_URI),
          stellarPaymentObserverConfig,
          null,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    every { observer.startSSEStream() } returns stream
    observer.start()
    observer.handleFailure(SSLProtocolException(""))
    assertEquals(ObserverStatus.STREAM_ERROR, observer.status)

    val checkResult = observer.check()
    assertEquals(RED, checkResult.status)
  }

  @Test
  fun `check() maps PUBLISHER_ERROR to RED`() {
    val observer =
      HorizonPaymentObserver(
        Horizon(TEST_HORIZON_URI),
        stellarPaymentObserverConfig,
        null,
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
      )
    observer.setStatus(ObserverStatus.PUBLISHER_ERROR)

    assertEquals(RED, observer.check().status)
  }

  @Test
  fun `processOperation does not advance the cursor when the operation cannot be converted`() {
    val observer =
      spyk(
        HorizonPaymentObserver(
          Horizon(TEST_HORIZON_URI),
          stellarPaymentObserverConfig,
          null,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    val operationResponse = mockk<OperationResponse>(relaxed = true)
    every { operationResponse.pagingToken } returns "999"
    every { observer.toPaymentTransferEvent(operationResponse) } throws
      org.stellar.anchor.api.exception.LedgerException("boom")

    observer.processOperation(operationResponse)

    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
    assertEquals(ObserverStatus.PUBLISHER_ERROR, observer.status)
  }

  @Test
  fun `processOperation skips an operation whose transaction cannot be decoded and keeps running`() {
    val observer =
      spyk(
        HorizonPaymentObserver(
          Horizon(TEST_HORIZON_URI),
          stellarPaymentObserverConfig,
          null,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    val operationResponse = mockk<OperationResponse>(relaxed = true)
    every { operationResponse.pagingToken } returns "999"
    every { observer.toPaymentTransferEvent(operationResponse) } throws
      org.stellar.anchor.api.exception.LedgerDecodeException(
        "Unable to parse transaction envelope",
        IllegalArgumentException("Unknown enum value: 22"),
      )

    val registry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
    io.micrometer.core.instrument.Metrics.addRegistry(registry)
    try {
      observer.processOperation(operationResponse)

      verify(exactly = 1) { paymentStreamerCursorStore.saveHorizonCursor("999") }
      assertNotEquals(ObserverStatus.PUBLISHER_ERROR, observer.status)
      assertEquals(
        1.0,
        registry
          .counter(
            org.stellar.anchor.platform.service.AnchorMetrics.PAYMENT_OBSERVER_EVENT_SKIPPED
              .toString()
          )
          .count(),
      )
    } finally {
      io.micrometer.core.instrument.Metrics.removeRegistry(registry)
    }
  }

  @Test
  fun `processOperation advances the cursor once the operation is successfully processed`() {
    val observer =
      spyk(
        HorizonPaymentObserver(
          Horizon(TEST_HORIZON_URI),
          stellarPaymentObserverConfig,
          null,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    val operationResponse = mockk<OperationResponse>(relaxed = true)
    every { operationResponse.pagingToken } returns "1000"
    every { observer.toPaymentTransferEvent(operationResponse) } returns null

    observer.processOperation(operationResponse)

    verify(exactly = 1) { paymentStreamerCursorStore.saveHorizonCursor("1000") }
  }

  @Test
  fun `handleEvent still delivers to every listener but propagates a failing listener's exception`() {
    val brokenListener: PaymentListener = mockk()
    val healthyListener: PaymentListener = mockk(relaxed = true)
    every { brokenListener.onReceived(any()) } throws IllegalStateException("decode bug")

    val observer =
      HorizonPaymentObserver(
        Horizon(TEST_HORIZON_URI),
        stellarPaymentObserverConfig,
        listOf(brokenListener, healthyListener),
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
      )
    val transferEvent = mockk<org.stellar.anchor.ledger.PaymentTransferEvent>()

    assertThrows<IllegalStateException> { observer.handleEvent(transferEvent) }

    verify(exactly = 1) { healthyListener.onReceived(transferEvent) }
  }

  @Test
  fun `processOperation sets PUBLISHER_ERROR and does not advance the cursor when a listener fails`() {
    val brokenListener: PaymentListener = mockk()
    every { brokenListener.onReceived(any()) } throws IllegalStateException("decode bug")

    val observer =
      spyk(
        HorizonPaymentObserver(
          Horizon(TEST_HORIZON_URI),
          stellarPaymentObserverConfig,
          listOf(brokenListener),
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    val operationResponse = mockk<OperationResponse>(relaxed = true)
    every { operationResponse.pagingToken } returns "PUBLISHER_ERROR_CUR"
    every { observer.toPaymentTransferEvent(operationResponse) } returns
      mockk<org.stellar.anchor.ledger.PaymentTransferEvent>()

    observer.processOperation(operationResponse)

    verify(exactly = 0) { paymentStreamerCursorStore.saveHorizonCursor(any()) }
    assertEquals(ObserverStatus.PUBLISHER_ERROR, observer.status)
  }

  private fun newSpiedHorizonObserver() =
    spyk(
      HorizonPaymentObserver(
        Horizon(TEST_HORIZON_URI),
        stellarPaymentObserverConfig,
        null,
        paymentObservingAccountsManager,
        paymentStreamerCursorStore,
      )
    )

  @Test
  fun `start does not propagate a startInternal failure and leaves the observer in STREAM_ERROR`() {
    val observer = newSpiedHorizonObserver()
    every { observer.startSSEStream() } throws RuntimeException("Horizon is down")
    // HZSTART-01
    assertDoesNotThrow { observer.start() }
    assertEquals(ObserverStatus.STREAM_ERROR, observer.status)
    observer.statusWatcher.shutdownNow()
    observer.silenceWatcher.shutdownNow()
  }

  @Test
  fun `start sets DATABASE_ERROR when startInternal fails with a TransactionException`() {
    val observer = newSpiedHorizonObserver()
    every { observer.startSSEStream() } throws CannotCreateTransactionException("db down")
    // HZSTART-04
    assertDoesNotThrow { observer.start() }
    assertEquals(ObserverStatus.DATABASE_ERROR, observer.status)
    observer.statusWatcher.shutdownNow()
    observer.silenceWatcher.shutdownNow()
  }

  @Test
  fun `start logs a startInternal failure at ERROR level`() {
    val observer = newSpiedHorizonObserver()
    val failure = RuntimeException("Horizon is down")
    every { observer.startSSEStream() } throws failure
    mockkStatic(org.stellar.anchor.util.Log::class)
    try {
      observer.start()
      // HZSTART-03
      verify { org.stellar.anchor.util.Log.errorEx(any<String>(), failure) }
    } finally {
      unmockkStatic(org.stellar.anchor.util.Log::class)
      observer.statusWatcher.shutdownNow()
      observer.silenceWatcher.shutdownNow()
    }
  }

  @Test
  fun `start schedules the status watcher before it invokes startInternal`() {
    val observer = newSpiedHorizonObserver()
    var queuedWatcherTasksAtStart = -1
    every { observer.startSSEStream() } answers
      {
        queuedWatcherTasksAtStart =
          (observer.statusWatcher as java.util.concurrent.ScheduledThreadPoolExecutor).queue.size
        mockk<SSEStream<OperationResponse>>(relaxed = true)
      }
    observer.start()
    // HZSTART-02
    assertEquals(1, queuedWatcherTasksAtStart)
    observer.statusWatcher.shutdownNow()
    observer.silenceWatcher.shutdownNow()
  }

  @Test
  fun `start sets RUNNING when startInternal succeeds`() {
    val observer = newSpiedHorizonObserver()
    every { observer.startSSEStream() } returns mockk<SSEStream<OperationResponse>>(relaxed = true)
    observer.status = ObserverStatus.STREAM_ERROR
    observer.start()
    // HZSTART-04
    assertEquals(ObserverStatus.RUNNING, observer.status)
    observer.statusWatcher.shutdownNow()
    observer.silenceWatcher.shutdownNow()
  }

  @Test
  fun `start arms the status watcher so a failed start recovers once Horizon is back`() {
    val observer = newSpiedHorizonObserver()
    val stream: SSEStream<OperationResponse> = mockk(relaxed = true)
    every { observer.startSSEStream() } throws RuntimeException("Horizon is down") andThen stream

    // HZSTART-02, HZSTART-04: no manual checkStatus(); only the watcher scheduled by start() runs.
    observer.start()
    assertEquals(ObserverStatus.STREAM_ERROR, observer.status)

    val deadline = System.currentTimeMillis() + 10_000
    while (observer.status != ObserverStatus.RUNNING && System.currentTimeMillis() < deadline) {
      Thread.sleep(100)
    }
    assertEquals(ObserverStatus.RUNNING, observer.status)
    verify(exactly = 2) { observer.startSSEStream() }
    observer.statusWatcher.shutdownNow()
    observer.silenceWatcher.shutdownNow()
  }

  private fun observerWithMockedLatestFetch(
    storedCursor: String?,
    latest: () -> Unit,
  ): HorizonPaymentObserver {
    every { paymentStreamerCursorStore.loadHorizonCursor() } returns storedCursor
    latest()
    return HorizonPaymentObserver(
      Horizon(TEST_HORIZON_URI),
      stellarPaymentObserverConfig,
      null,
      paymentObservingAccountsManager,
      paymentStreamerCursorStore,
    )
  }

  private fun mockLatestPayments(page: Page<OperationResponse>?, throwing: Throwable? = null) {
    mockkConstructor(Server::class)
    if (throwing != null) {
      every {
        constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
          .payments()
          .order(RequestBuilder.Order.DESC)
          .limit(1)
          .execute()
      } throws throwing
    } else {
      every {
        constructedWith<Server>(EqMatcher(TEST_HORIZON_URI))
          .payments()
          .order(RequestBuilder.Order.DESC)
          .limit(1)
          .execute()
      } returns page
    }
  }

  @Test
  fun `fetchStreamingCursor throws a RuntimeException other than NumberFormatException when a cursor is stored and the latest fetch fails`() {
    // HZCUR-02
    val observer =
      observerWithMockedLatestFetch("123") {
        mockLatestPayments(null, NetworkException(null, "Some IO Problem happened!"))
      }
    val ex = assertThrows<RuntimeException> { observer.fetchStreamingCursor() }
    assertTrue(ex !is NumberFormatException)
  }

  @Test
  fun `fetchStreamingCursor returns now when a cursor is stored and the network has no payments`() {
    // HZCUR-04
    val emptyPage: Page<OperationResponse> =
      GsonSingleton.getInstance()
        .fromJson(
          """{"_embedded": {"records": []}}""",
          object : TypeToken<Page<OperationResponse?>?>() {}.type,
        )
    val observer = observerWithMockedLatestFetch("123") { mockLatestPayments(emptyPage) }
    assertEquals("now", observer.fetchStreamingCursor())
  }

  @Test
  fun `fetchStreamingCursor returns the latest cursor when the stored cursor is not behind it`() {
    // HZCUR-05
    val observer = spyk(observerWithMockedLatestFetch("2000") {})
    every { observer.fetchLatestCursorFromHorizon() } returns "1000"
    assertEquals("1000", observer.fetchStreamingCursor())
    every { paymentStreamerCursorStore.loadHorizonCursor() } returns "1000"
    assertEquals("1000", observer.fetchStreamingCursor())
  }

  @Test
  fun `fetchStreamingCursor throws a RuntimeException when the stored cursor is not a number`() {
    // HZREG-01
    val observer = spyk(observerWithMockedLatestFetch("not-a-number") {})
    every { observer.fetchLatestCursorFromHorizon() } returns "1000"
    assertThrows<RuntimeException> { observer.fetchStreamingCursor() }
  }

  @Test
  fun `startSSEStream does not open a payments stream when the cursor cannot be determined`() {
    // HZCUR-03
    val horizon: Horizon = mockk(relaxed = true)
    val observer =
      spyk(
        HorizonPaymentObserver(
          horizon,
          stellarPaymentObserverConfig,
          null,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    every { observer.fetchStreamingCursor() } throws RuntimeException("Horizon is down")
    assertThrows<RuntimeException> { observer.startSSEStream() }
    verify(exactly = 0) { horizon.server }
  }

  private val elderLedger = 1000L
  private val elderBoundary = elderLedger shl 32
  private val latestCursor = "9000000000000000"

  private fun rootResponse(json: String): org.stellar.sdk.responses.RootResponse =
    GsonSingleton.getInstance().fromJson(json, org.stellar.sdk.responses.RootResponse::class.java)

  private fun retentionObserver(
    stored: String?,
    latest: String = latestCursor
  ): HorizonPaymentObserver {
    every { paymentStreamerCursorStore.loadHorizonCursor() } returns stored
    val observer =
      spyk(
        HorizonPaymentObserver(
          Horizon(TEST_HORIZON_URI),
          stellarPaymentObserverConfig,
          null,
          paymentObservingAccountsManager,
          paymentStreamerCursorStore,
        )
      )
    every { observer.fetchLatestCursorFromHorizon() } returns latest
    return observer
  }

  private fun mockRoot(
    response: org.stellar.sdk.responses.RootResponse?,
    throwing: Throwable? = null
  ) {
    mockkConstructor(Server::class)
    if (throwing != null) {
      every { constructedWith<Server>(EqMatcher(TEST_HORIZON_URI)).root().execute() } throws
        throwing
    } else {
      every { constructedWith<Server>(EqMatcher(TEST_HORIZON_URI)).root().execute() } returns
        response!!
    }
  }

  @Test
  fun `fetchStreamingCursor rejects a stored cursor at or below the elder ledger`() {
    // HZRET-01, HZRET-02
    mockRoot(rootResponse("""{"history_elder_ledger": $elderLedger}"""))
    for (stored in listOf("1", (elderBoundary - 1).toString(), elderBoundary.toString())) {
      val observer = retentionObserver(stored)
      val ex = assertThrows<RuntimeException> { observer.fetchStreamingCursor() }
      assertTrue(
        ex.message!!.contains(stored),
        "message should name the stored cursor: ${ex.message}"
      )
      assertTrue(
        ex.message!!.contains(elderLedger.toString()),
        "message should name the elder ledger"
      )
    }
  }

  @Test
  fun `fetchStreamingCursor keeps a stored cursor after the elder ledger`() {
    // HZRET-02 (accept side)
    mockRoot(rootResponse("""{"history_elder_ledger": $elderLedger}"""))
    val stored = (elderBoundary + 1).toString()
    assertEquals(stored, retentionObserver(stored).fetchStreamingCursor())
  }

  @Test
  fun `fetchStreamingCursor keeps the stored cursor when the root response has no elder ledger`() {
    // HZRET-03
    mockRoot(rootResponse("""{}"""))
    assertEquals("5", retentionObserver("5").fetchStreamingCursor())
  }

  @Test
  fun `fetchStreamingCursor throws a RuntimeException when the root request fails`() {
    // HZRET-04
    mockRoot(null, NetworkException(null, "Some IO Problem happened!"))
    assertThrows<RuntimeException> { retentionObserver("5").fetchStreamingCursor() }
  }

  @Test
  fun `fetchStreamingCursor does not request the root resource when there is nothing to protect`() {
    // HZRET-05
    mockRoot(rootResponse("""{"history_elder_ledger": $elderLedger}"""))
    retentionObserver(null).fetchStreamingCursor()
    retentionObserver("9000000000000001").fetchStreamingCursor()
    retentionObserver("5", "now").fetchStreamingCursor()
    verify(exactly = 0) { constructedWith<Server>(EqMatcher(TEST_HORIZON_URI)).root() }
  }
}
