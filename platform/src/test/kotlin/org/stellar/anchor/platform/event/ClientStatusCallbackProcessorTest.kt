package org.stellar.anchor.platform.event

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.FileNotFoundException
import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.asset.AssetInfo
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.InternalServerErrorException
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.SEP_24
import org.stellar.anchor.api.sep.SepTransactionStatus.COMPLETED
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.RefundPayment
import org.stellar.anchor.api.shared.Refunds
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.client.ClientConfig.CallbackUrls
import org.stellar.anchor.client.CustodialClient
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue
import org.stellar.anchor.platform.config.PropertySecretConfig
import org.stellar.anchor.platform.service.Sep24MoreInfoUrlConstructor
import org.stellar.anchor.platform.service.Sep6MoreInfoUrlConstructor
import org.stellar.anchor.platform.utils.setupMock
import org.stellar.anchor.util.ExponentialBackoffTimer

class ClientStatusCallbackProcessorTest {
  @MockK(relaxed = true) lateinit var event: AnchorEvent
  @MockK(relaxed = true) lateinit var eventHandler: ClientStatusCallbackHandler
  @MockK(relaxed = true) lateinit var eventService: EventService
  @MockK(relaxed = true) lateinit var httpErrorBackoffTimer: ExponentialBackoffTimer
  @MockK(relaxed = true) lateinit var networkErrorBackoffTimer: ExponentialBackoffTimer
  private lateinit var eventProcessor: ClientStatusCallbackProcessor

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this)
    eventProcessor =
      spyk(
        ClientStatusCallbackProcessor(
          "TEST PROCESSOR",
          EventQueue.TRANSACTION,
          eventService,
          eventHandler
        )
      )
  }

  @Test
  fun `test that the event is not retried if the event handler returns true`() {
    every { eventHandler.handleEvent(event) } returns true
    eventProcessor.handleEventWithRetry(event)
    // Check if handleEvent is called only once
    verify(exactly = 1) { eventHandler.handleEvent(any()) }
    // Check if incrementProcessCount is called
    verify(exactly = 1) { eventProcessor.incrementProcessedCounter() }
  }

  @Test
  fun `test that when event handler returns false httpErrorBackoffTimer backoff() is called 3 times`() {
    every { eventHandler.handleEvent(event) } returns false
    every { eventProcessor.httpErrorBackoffTimer } returns httpErrorBackoffTimer
    every { eventProcessor.networkBackoffTimer } returns networkErrorBackoffTimer
    eventProcessor.handleEventWithRetry(event)

    // Check if handleEvent is called 3 times
    verify(exactly = 3) { eventHandler.handleEvent(any()) }
    // Check if the timer is called 2 times when the handleEvent is called 3 times.
    verify(exactly = 2) { httpErrorBackoffTimer.backoff() }
    // Eventually, we mark it successful
    verify(exactly = 1) { eventProcessor.incrementProcessedCounter() }
  }

  @ParameterizedTest
  @ValueSource(ints = [1, 3, 5, 10, 100])
  fun `test that when event handler throws IOException, networkBackoffTimer is called at most 3 times`(
    attempts: Int
  ) {
    var counter = attempts
    every { eventHandler.handleEvent(event) } answers { throw IOException("Mock exception") }
    every { eventProcessor.httpErrorBackoffTimer } returns httpErrorBackoffTimer
    every { eventProcessor.networkBackoffTimer } returns networkErrorBackoffTimer
    every { networkErrorBackoffTimer.backoff() } answers
      {
        counter--
        if (counter == 0) {
          Thread.currentThread().interrupt()
        }
      }

    eventProcessor.handleEventWithRetry(event)

    // Check if handleEvent is called `attempts` times
    verify(atLeast = 1, atMost = 3) { eventHandler.handleEvent(any()) }
    // Check if the timer is called `attempts` times when the handleEvent is called at most 3 times.
    verify(atLeast = 1, atMost = 3) { networkErrorBackoffTimer.backoff() }
    // Make sure the metric does not show it passes before running out of retry
    if (attempts < 3) {
      // If thread interrupted before 3 retries
      verify(exactly = 0) { eventProcessor.incrementProcessedCounter() }
    } else {
      verify(exactly = 1) { eventProcessor.incrementProcessedCounter() }
    }
  }

  @ParameterizedTest
  @ValueSource(
    classes = [RuntimeException::class, SepException::class, FileNotFoundException::class]
  )
  fun `test that when event handler throws uncaught exception, sendToDLQ is called once`(
    clz: Class<Throwable>
  ) {
    // Mock handleEvent to throw uncaught exception
    every { eventHandler.handleEvent(event) } answers
      {
        throw clz.getConstructor(String::class.java).newInstance()
      }

    every { eventProcessor.httpErrorBackoffTimer } returns httpErrorBackoffTimer
    every { eventProcessor.networkBackoffTimer } returns networkErrorBackoffTimer

    eventProcessor.handleEventWithRetry(event)

    // Check if handleEvent is called 1 times
    verify(exactly = 1) { eventHandler.handleEvent(any()) }
    // Check if the timer is called 1 times when the handleEvent is called 3 times.
    verify(exactly = 1) { eventProcessor.sendToDLQ(any(), any()) }
    // Check that no backoff is called
    verify(exactly = 0) { networkErrorBackoffTimer.backoff() }
    verify(exactly = 0) { httpErrorBackoffTimer.backoff() }
    // Make sure the metric does not show it passes
    verify(exactly = 0) { eventProcessor.incrementProcessedCounter() }
  }

  @Test
  fun `redirect failure is retried three times and then skipped`() {
    val assetInfo = mockk<AssetInfo>()
    every { assetInfo.significantDecimals } returns 7
    val assetService = mockk<AssetService>()
    every { assetService.getAsset(any(), any()) } returns assetInfo
    val sep6MoreInfoUrlConstructor = mockk<Sep6MoreInfoUrlConstructor>()
    val sep24MoreInfoUrlConstructor = mockk<Sep24MoreInfoUrlConstructor>()
    every { sep6MoreInfoUrlConstructor.construct(any(), any()) } returns "https://example.com"
    every { sep24MoreInfoUrlConstructor.construct(any(), any()) } returns "https://example.com"
    val secretConfig = mockk<PropertySecretConfig>()
    secretConfig.setupMock()

    val server = MockWebServer()
    server.start()
    try {
      repeat(3) { server.enqueue(MockResponse().setResponseCode(302)) }
      val client =
        CustodialClient.builder()
          .name("circle")
          .signingKeys(setOf("GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2"))
          .callbackUrls(
            CallbackUrls.builder().sep24(server.url("/callback/sep24").toString()).build()
          )
          .allowAnyDestination(false)
          .destinationAccounts(emptySet())
          .build()
      val realHandler =
        ClientStatusCallbackHandler(
          secretConfig,
          client,
          assetService,
          sep6MoreInfoUrlConstructor,
          sep24MoreInfoUrlConstructor
        )
      val processor =
        spyk(
          ClientStatusCallbackProcessor(
            "TEST PROCESSOR",
            EventQueue.TRANSACTION,
            eventService,
            realHandler
          )
        )
      every { processor.httpErrorBackoffTimer } returns httpErrorBackoffTimer
      every { processor.networkBackoffTimer } returns networkErrorBackoffTimer
      val redirectedEvent =
        AnchorEvent().apply {
          transaction =
            GetTransactionResponse().apply {
              id = "redirected"
              sep = SEP_24
              kind = Kind.WITHDRAWAL
              status = COMPLETED
              clientName = "circle"
              refunds =
                Refunds.builder()
                  .amountRefunded(Amount("1", "USD"))
                  .amountFee(Amount("0", "USD"))
                  .payments(
                    arrayOf(
                      RefundPayment.builder()
                        .id("refund-redirected")
                        .idType(RefundPayment.IdType.EXTERNAL)
                        .amount(Amount("1", "USD"))
                        .fee(Amount("0", "USD"))
                        .build()
                    )
                  )
                  .build()
            }
        }

      processor.handleEventWithRetry(redirectedEvent)

      assertEquals(3, server.requestCount)
      verify(exactly = 2) { httpErrorBackoffTimer.backoff() }
      verify(exactly = 1) { processor.incrementProcessedCounter() }
    } finally {
      server.shutdown()
    }
  }

  private fun rejectedBatchSetup(
    events: List<AnchorEvent>,
    batchSize: Int = 50,
    failAckAt: Int? = null,
  ): RecordingSession {
    every { eventProcessor.httpErrorBackoffTimer } returns httpErrorBackoffTimer
    every { eventProcessor.networkBackoffTimer } returns networkErrorBackoffTimer
    val session =
      RecordingSession(
        events.chunked(batchSize),
        failAckAt,
        onExhausted = { eventProcessor.stop() }
      )
    every { eventService.createSession(any(), any()) } returns session
    return session
  }

  private fun anchorEvents(count: Int, sep: String = "24") =
    (1..count).map { AnchorEvent.builder().id("e$it").sep(sep).build() }

  @Test
  fun `150 events the wallet rejects are each handled three times and committed one by one`() {
    // #4184521 scenario A: the wallet answers an error for every event it did not create.
    every { eventHandler.handleEvent(any()) } returns false
    val session = rejectedBatchSetup(anchorEvents(150))

    eventProcessor.run()

    verify(exactly = 450) { eventHandler.handleEvent(any()) }
    verify(exactly = 300) { httpErrorBackoffTimer.backoff() }
    val expectedAcks = (1..3).flatMap { (1..50).map { n -> "ack:$n" } + "ack:batch" }
    assertEquals(expectedAcks, session.calls.filter { it.startsWith("ack") })
    // one keep-alive after each of the 2 waits per event and one after each event
    assertEquals(450, session.calls.count { it == "keepAlive" })
  }

  @Test
  fun `700 events the wallet accepts are each handled once and committed one by one`() {
    // #4184521 scenario B: the wallet answers 200 but slowly, 500+ records in a poll.
    val handledCount = mutableMapOf<String, Int>()
    every { eventHandler.handleEvent(any()) } answers
      {
        handledCount.merge(firstArg<AnchorEvent>().id, 1, Int::plus)
        true
      }
    val session = rejectedBatchSetup(anchorEvents(700))

    eventProcessor.run()

    assertEquals(700, handledCount.size)
    assertEquals(setOf(1), handledCount.values.toSet())
    assertEquals(
      (1..14).flatMap { (1..50).map { n -> "ack:$n" } + "ack:batch" },
      session.calls.filter { it.startsWith("ack") },
    )
  }

  @Test
  fun `events committed before a failed commit are not handled again by the next session`() {
    val handledCount = mutableMapOf<String, Int>()
    every { eventHandler.handleEvent(any()) } answers
      {
        handledCount.merge(firstArg<AnchorEvent>().id, 1, Int::plus)
        true
      }
    val events = anchorEvents(50)
    every { eventProcessor.httpErrorBackoffTimer } returns httpErrorBackoffTimer
    every { eventProcessor.networkBackoffTimer } returns networkErrorBackoffTimer
    val first = RecordingSession(listOf(events), failAckAt = 30, onExhausted = {})
    // Kafka re-reads from the committed offset: the 30th event was handled but not committed.
    val second = RecordingSession(listOf(events.drop(29)), onExhausted = { eventProcessor.stop() })
    every { eventService.createSession(any(), any()) } returnsMany listOf(first, second)

    eventProcessor.run()
    eventProcessor.run()

    assertEquals(50, handledCount.size)
    assertEquals(setOf(1), events.take(29).map { handledCount[it.id] }.toSet())
    assertEquals(2, handledCount["e30"])
    assertEquals(setOf(1), events.drop(30).map { handledCount[it.id] }.toSet())
  }

  @Test
  fun `a SEP-6, a SEP-31 and a SEP-12 event are each committed once`() {
    every { eventHandler.handleEvent(any()) } returns true
    val events =
      listOf("6", "31", "12").map { AnchorEvent.builder().id("event-sep$it").sep(it).build() }
    val session = rejectedBatchSetup(events)

    eventProcessor.run()

    verify(exactly = 3) { eventHandler.handleEvent(any()) }
    assertEquals(
      listOf("ack:1", "ack:2", "ack:3", "ack:batch"),
      session.calls.filter { it.startsWith("ack") },
    )
  }

  @Test
  fun `a connection error keeps the consumer alive after each wait and before the commit`() {
    every { eventHandler.handleEvent(any()) } answers { throw IOException("down") }
    val session = rejectedBatchSetup(anchorEvents(1))

    eventProcessor.run()

    verify(exactly = 2) { networkErrorBackoffTimer.backoff() }
    assertEquals(
      listOf("read", "keepAlive", "keepAlive", "ack:1", "keepAlive", "ack:batch", "read"),
      session.calls.takeWhile { it != "close" },
    )
  }
}

private class RecordingSession(
  private val batches: List<List<AnchorEvent>>,
  private val failAckAt: Int? = null,
  private val onExhausted: () -> Unit,
) : EventService.Session {
  val calls = mutableListOf<String>()
  private var nextBatch = 0

  private fun response(list: List<AnchorEvent>) =
    object : EventService.ReadResponse {
      override fun getEvents(): List<AnchorEvent> = list
    }

  override fun publish(event: AnchorEvent) {}

  override fun read(): EventService.ReadResponse {
    calls += "read"
    if (nextBatch >= batches.size) {
      onExhausted()
      return response(emptyList())
    }
    return response(batches[nextBatch++])
  }

  override fun ack(readResponse: EventService.ReadResponse) {
    calls += "ack:batch"
  }

  override fun ack(readResponse: EventService.ReadResponse, handledCount: Int) {
    calls += "ack:$handledCount"
    if (handledCount == failAckAt) throw InternalServerErrorException("commit failed")
  }

  override fun keepAlive() {
    calls += "keepAlive"
  }

  override fun close() {
    calls += "close"
  }

  override fun getSessionName() = "recording"
}
