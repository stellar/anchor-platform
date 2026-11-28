package org.stellar.anchor.platform.event

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.IOException
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.HttpException
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.shared.StellarTransaction
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.event.EventService
import org.stellar.anchor.platform.config.CallbackApiConfig
import org.stellar.anchor.util.ExponentialBackoffTimer

class CallbackApiEventProcessorTest {
  @MockK(relaxed = true) lateinit var event: AnchorEvent
  @MockK(relaxed = true) lateinit var eventHandler: CallbackApiEventHandler
  @MockK(relaxed = true) lateinit var eventService: EventService
  @MockK(relaxed = true) lateinit var backoffTimer: ExponentialBackoffTimer
  private lateinit var eventProcessor: CallbackApiEventProcessor

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this)
    eventProcessor =
      spyk(
        CallbackApiEventProcessor(
          "TEST PROCESSOR",
          EventService.EventQueue.TRANSACTION,
          eventService,
          eventHandler
        )
      )
  }

  @Test
  fun `test event handled successfully without retry`() {
    every { eventHandler.handleEvent(event) } returns true
    eventProcessor.handleEventWithRetry(event)
    verify(exactly = 1) { eventHandler.handleEvent(any()) }
    verify(exactly = 1) { eventProcessor.incrementProcessedCounter() }
  }

  @ParameterizedTest
  @ValueSource(ints = [1, 3, 5, 10, 100])
  fun `test event retry on IOException and stops on InterruptedException`(attempts: Int) {
    var counter = attempts
    every { eventHandler.handleEvent(event) } answers { throw IOException("Mock exception") }
    every { eventProcessor.backoffTimer } returns backoffTimer
    every { backoffTimer.backoff() } answers
      {
        counter--
        if (counter == 0) {
          Thread.currentThread().interrupt()
        }
      }

    eventProcessor.handleEventWithRetry(event)
    verify(exactly = 0) { eventProcessor.incrementProcessedCounter() }
    verify(exactly = attempts) { eventHandler.handleEvent(event) }
    verify(exactly = attempts) { backoffTimer.backoff() }
  }

  @ParameterizedTest
  @ValueSource(ints = [400, 409, 413, 415, 422])
  fun `test event rejected by the business server is dropped after bounded retries`(status: Int) {
    every { eventHandler.handleEvent(event) } throws HttpException(status)
    every { eventProcessor.backoffTimer } returns backoffTimer

    eventProcessor.handleEventWithRetry(event)

    val attempts = CallbackApiEventProcessor.MAX_REJECTED_ATTEMPTS
    verify(exactly = attempts) { eventHandler.handleEvent(event) }
    verify(exactly = attempts - 1) { backoffTimer.backoff() }
    verify(exactly = 1) { eventProcessor.dropEvent(event, status) }
    verify(exactly = 0) { eventProcessor.incrementProcessedCounter() }
    assertFalse(Thread.currentThread().isInterrupted)
  }

  @ParameterizedTest
  @ValueSource(ints = [401, 403, 404, 408, 429, 500, 502, 503])
  fun `test event retry is unbounded on errors unrelated to the event`(status: Int) {
    val attempts = CallbackApiEventProcessor.MAX_REJECTED_ATTEMPTS * 4
    var counter = attempts
    every { eventHandler.handleEvent(event) } throws HttpException(status)
    every { eventProcessor.backoffTimer } returns backoffTimer
    every { backoffTimer.backoff() } answers
      {
        counter--
        if (counter == 0) {
          Thread.currentThread().interrupt()
        }
      }

    eventProcessor.handleEventWithRetry(event)
    Thread.interrupted()

    verify(exactly = attempts) { eventHandler.handleEvent(event) }
    verify(exactly = 0) { eventProcessor.dropEvent(any(), any()) }
    verify(exactly = 0) { eventProcessor.incrementProcessedCounter() }
  }

  @Test
  fun `test rejected attempts are counted per event`() {
    every { eventProcessor.backoffTimer } returns backoffTimer
    var calls = 0
    every { eventHandler.handleEvent(event) } answers
      {
        if (++calls < 3) throw HttpException(413)
        true
      }

    eventProcessor.handleEventWithRetry(event)
    every { eventHandler.handleEvent(event) } throws HttpException(413)
    eventProcessor.handleEventWithRetry(event)

    verify(exactly = 3 + CallbackApiEventProcessor.MAX_REJECTED_ATTEMPTS) {
      eventHandler.handleEvent(event)
    }
    verify(exactly = 1) { eventProcessor.incrementProcessedCounter() }
    verify(exactly = 1) { eventProcessor.dropEvent(event, 413) }
  }

  @Test
  fun `test an event the business server rejects does not block the events after it`() {
    val server = MockWebServer()
    val bodyLimit = 100 * 1024
    val delivered = mutableListOf<String>()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (request.bodySize > bodyLimit) return MockResponse().setResponseCode(413)
          delivered.add(request.body.readUtf8())
          return MockResponse().setResponseCode(200).setBody("{}")
        }
      }
    server.start()
    try {
      val config = mockk<CallbackApiConfig>()
      every { config.buildAuthHelper() } returns AuthHelper.forNone()
      every { config.baseUrl } returns server.url("/").toString()
      val poisoned = withdrawalEvent("poisoned", "A".repeat(bodyLimit + 1))
      val queued = (1..3).map { withdrawalEvent("queued-$it", "AAAA") }

      val response = mockk<EventService.ReadResponse>()
      every { response.events } returns listOf(poisoned) + queued
      val empty = mockk<EventService.ReadResponse>()
      every { empty.events } returns emptyList()
      val session = mockk<EventService.Session>(relaxed = true)
      every { session.read() } returns
        response andThenAnswer
        {
          Thread.currentThread().interrupt()
          empty
        }
      every { eventService.createSession(any(), any()) } returns session

      val processor =
        spyk(
          CallbackApiEventProcessor(
            "TEST PROCESSOR",
            EventService.EventQueue.TRANSACTION,
            eventService,
            CallbackApiEventHandler(config),
          )
        )
      every { processor.backoffTimer } returns backoffTimer

      processor.run()
      Thread.interrupted()

      assertEquals(CallbackApiEventProcessor.MAX_REJECTED_ATTEMPTS + 3, server.requestCount)
      assertEquals(3, delivered.size)
      queued.forEachIndexed { i, e -> assertTrue(delivered[i].contains(e.id)) }
      verify(exactly = 1) { processor.dropEvent(poisoned, 413) }
      verify(exactly = 1) { session.ack(response) }
    } finally {
      server.shutdown()
    }
  }

  private fun withdrawalEvent(id: String, envelope: String) =
    AnchorEvent.builder()
      .id(id)
      .sep("24")
      .type(AnchorEvent.Type.TRANSACTION_STATUS_CHANGED)
      .transaction(
        GetTransactionResponse.builder()
          .id("txn-$id")
          .stellarTransactions(
            listOf(StellarTransaction.builder().id("hash-$id").envelope(envelope).build())
          )
          .build()
      )
      .build()

  @Test
  fun `test isEventRejected classifies client errors that reject the event`() {
    listOf(400, 409, 413, 414, 415, 422).forEach {
      assertTrue(CallbackApiEventProcessor.isEventRejected(it))
    }
    listOf(200, 302, 401, 403, 404, 408, 429, 500, 503).forEach {
      assertFalse(CallbackApiEventProcessor.isEventRejected(it))
    }
  }
}
