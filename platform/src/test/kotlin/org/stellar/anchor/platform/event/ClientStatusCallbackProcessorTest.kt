package org.stellar.anchor.platform.event

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue
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
}
