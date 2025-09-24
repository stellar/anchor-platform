package org.stellar.anchor.platform.event

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.IOException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.event.EventService
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
}
