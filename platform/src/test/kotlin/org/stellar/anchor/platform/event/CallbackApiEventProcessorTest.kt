package org.stellar.anchor.platform.event

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
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

  private fun sessionOf(events: List<AnchorEvent>, batchSize: Int = 50): RecordingSession {
    every { eventProcessor.backoffTimer } returns backoffTimer
    val session =
      RecordingSession(events.chunked(batchSize), onExhausted = { eventProcessor.stop() })
    every { eventService.createSession(any(), any()) } returns session
    return session
  }

  private fun anchorEvents(count: Int) = (1..count).map { AnchorEvent.builder().id("e$it").build() }

  @Test
  fun `the wait between retries doubles from 1 second and never passes 30 seconds`() {
    val timer =
      CallbackApiEventProcessor(
          "t",
          EventService.EventQueue.TRANSACTION,
          eventService,
          eventHandler
        )
        .backoffTimer
    val increase =
      ExponentialBackoffTimer::class.java.getDeclaredMethod("increase").apply {
        isAccessible = true
      }

    val waits = mutableListOf(timer.currentTimer())
    repeat(7) {
      increase.invoke(timer)
      waits += timer.currentTimer()
    }

    // 16 doubles to 32, which the cap holds at exactly 30; nothing ever exceeds 30
    assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L), waits)
  }

  @Test
  fun `events delivered before an outage are delivered once and the event in flight is delivered once after it`() {
    // #4237411 outage: the business server is down for 12 consecutive attempts of event 9, which
    // is longer than the 255 s of cumulative waits the old 300 s cap needed to pass the interval.
    val delivered = mutableMapOf<String, Int>()
    var failures = 0
    every { eventHandler.handleEvent(any()) } answers
      {
        val id = firstArg<AnchorEvent>().id
        if (id == "e9" && failures++ < 12) {
          false
        } else {
          delivered.merge(id, 1, Int::plus)
          true
        }
      }
    val session = sessionOf(anchorEvents(20))

    eventProcessor.run()

    assertEquals(20, delivered.size)
    assertEquals(setOf(1), delivered.values.toSet())
    assertEquals(
      (1..20).map { "ack:$it" } + "ack:batch",
      session.calls.filter { it.startsWith("ack") },
    )
    verify(exactly = 12) { backoffTimer.backoff() }
    // one keep-alive after each of the 12 waits and one after each of the 20 events
    assertEquals(32, session.calls.count { it == "keepAlive" })
  }

  @Test
  fun `80 slow deposits are each delivered once and committed one by one`() {
    // #4237411 burst: 80 events, more than one poll of 50 records.
    val delivered = mutableMapOf<String, Int>()
    every { eventHandler.handleEvent(any()) } answers
      {
        delivered.merge(firstArg<AnchorEvent>().id, 1, Int::plus)
        true
      }
    val session = sessionOf(anchorEvents(80))

    eventProcessor.run()

    assertEquals(80, delivered.size)
    assertEquals(setOf(1), delivered.values.toSet())
    assertEquals(
      (1..50).map { "ack:$it" } + "ack:batch" + (1..30).map { "ack:$it" } + "ack:batch",
      session.calls.filter { it.startsWith("ack") },
    )
  }

  @Test
  fun `a rejected event keeps the consumer alive after each wait and before the retry`() {
    var attempts = 0
    every { eventHandler.handleEvent(any()) } answers { ++attempts > 2 }
    val session = sessionOf(anchorEvents(1))

    eventProcessor.run()

    assertEquals(
      listOf("read", "keepAlive", "keepAlive", "ack:1", "keepAlive", "ack:batch", "read"),
      session.calls.takeWhile { it != "close" },
    )
  }

  @Test
  fun `a connection error keeps the consumer alive after each wait and before the retry`() {
    var attempts = 0
    every { eventHandler.handleEvent(any()) } answers
      {
        if (++attempts > 2) true else throw IOException("down")
      }
    val session = sessionOf(anchorEvents(1))

    eventProcessor.run()

    assertEquals(
      listOf("read", "keepAlive", "keepAlive", "ack:1", "keepAlive", "ack:batch", "read"),
      session.calls.takeWhile { it != "close" },
    )
  }
}
