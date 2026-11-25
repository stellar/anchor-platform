package org.stellar.anchor.platform.event

import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.InternalServerErrorException
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue

class EventProcessorLoopTest {
  companion object {
    const val POLL_INTERVAL_MINUTES = 15L
  }

  private class Batch(val list: List<AnchorEvent>) : EventService.ReadResponse {
    override fun getEvents(): List<AnchorEvent> = list
  }

  /**
   * A queue session that behaves like Kafka for the parts the loop depends on: a consumer that does
   * not poll for longer than the poll interval is evicted and its commits fail.
   */
  private class FakeSession(
    private val clock: AtomicLong,
    private val batches: List<List<AnchorEvent>>,
    private val failAckAt: Int? = null,
    private val failKeepAliveAt: Int? = null,
    private val onExhausted: () -> Unit,
    private val onRead: () -> Unit = {},
  ) : EventService.Session {
    val calls = mutableListOf<String>()
    private var nextBatch = 0
    private var keepAlives = 0
    private var lastPoll = clock.get()
    var evicted = false

    private fun checkAlive() {
      if (clock.get() - lastPoll > POLL_INTERVAL_MINUTES) evicted = true
      if (evicted) throw InternalServerErrorException("evicted: poll interval exceeded")
    }

    override fun publish(event: AnchorEvent) {}

    override fun read(): EventService.ReadResponse {
      calls += "read"
      lastPoll = clock.get()
      onRead()
      if (nextBatch >= batches.size) {
        onExhausted()
        return Batch(emptyList())
      }
      return Batch(batches[nextBatch++])
    }

    override fun ack(readResponse: EventService.ReadResponse) {
      calls += "ack:batch"
      checkAlive()
    }

    override fun ack(readResponse: EventService.ReadResponse, handledCount: Int) {
      calls += "ack:$handledCount"
      if (handledCount == failAckAt) throw InternalServerErrorException("commit failed")
      checkAlive()
    }

    override fun keepAlive() {
      calls += "keepAlive"
      keepAlives++
      if (keepAlives == failKeepAliveAt) throw InternalServerErrorException("keep-alive failed")
      checkAlive()
      lastPoll = clock.get()
    }

    override fun close() {
      calls += "close"
    }

    override fun getSessionName() = "fake"
  }

  private class TestProcessor(
    eventService: EventService,
    val handler: (TestProcessor, AnchorEvent) -> Unit,
  ) : EventProcessor("test-processor", EventQueue.TRANSACTION, eventService) {
    val handled = mutableListOf<String>()

    override fun handleEventWithRetry(event: AnchorEvent) {
      handler(this, event)
    }

    override fun toMetricTag(queueName: String) = "test"

    fun callKeepAlive() = keepAlive()
  }

  private val clock = AtomicLong(0)

  private fun events(vararg ids: String) = ids.map { AnchorEvent.builder().id(it).build() }

  private fun serviceOf(vararg sessions: FakeSession): EventService {
    var next = 0
    return object : EventService {
      override fun createSession(name: String, eventQueue: EventQueue) = sessions[next++]
    }
  }

  @AfterEach
  fun clearInterruptFlag() {
    Thread.interrupted()
  }

  private fun recordingHandler(): (TestProcessor, AnchorEvent) -> Unit = { p, e ->
    p.handled += e.id
  }

  @Test
  fun `each handled event is committed then the consumer is kept alive then the batch is committed`() {
    lateinit var processor: TestProcessor
    val session =
      FakeSession(
        clock,
        listOf(events("e1", "e2", "e3")),
        onExhausted = { processor.stop() },
      )
    processor = TestProcessor(serviceOf(session), recordingHandler())

    processor.run()

    assertEquals(
      listOf(
        "read",
        "ack:1",
        "keepAlive",
        "ack:2",
        "keepAlive",
        "ack:3",
        "keepAlive",
        "ack:batch",
        "read",
      ),
      session.calls.takeWhile { it != "close" },
    )
    assertEquals(listOf("e1", "e2", "e3"), processor.handled)
  }

  @Test
  fun `a batch that takes longer than the poll interval in total delivers every event once`() {
    lateinit var processor: TestProcessor
    val session =
      FakeSession(clock, listOf(events("e1", "e2", "e3")), onExhausted = { processor.stop() })
    // 3 events x 10 minutes = 30 minutes, twice the 15-minute interval; each event alone is below
    // it
    processor =
      TestProcessor(serviceOf(session)) { p, e ->
        clock.addAndGet(10)
        p.handled += e.id
      }

    processor.run()

    assertEquals(listOf("e1", "e2", "e3"), processor.handled)
    assertFalse(session.evicted)
    assertEquals(
      listOf("ack:1", "ack:2", "ack:3", "ack:batch"),
      session.calls.filter { it.startsWith("ack") },
    )
  }

  @Test
  fun `a failed commit stops the batch and the next session does not handle earlier events again`() {
    lateinit var processor: TestProcessor
    val first =
      FakeSession(clock, listOf(events("e1", "e2", "e3")), failAckAt = 2, onExhausted = {})
    val second = FakeSession(clock, listOf(events("e2", "e3")), onExhausted = { processor.stop() })
    processor = TestProcessor(serviceOf(first, second), recordingHandler())

    processor.run()
    processor.run()

    // e1 was committed before the failure; e2 is the event in flight and is read once more.
    assertEquals(listOf("e1", "e2", "e2", "e3"), processor.handled)
    assertEquals("close", first.calls.last())
    assertEquals(listOf("ack:1", "ack:2"), first.calls.filter { it.startsWith("ack") })
  }

  @Test
  fun `an interrupt during an event commits the events before it and nothing else`() {
    val session = FakeSession(clock, listOf(events("e1", "e2", "e3")), onExhausted = {})
    val processor =
      TestProcessor(serviceOf(session)) { p, e ->
        if (e.id == "e2") {
          Thread.currentThread().interrupt()
        } else {
          p.handled += e.id
        }
      }

    processor.run()

    assertEquals(listOf("e1"), processor.handled)
    assertEquals(listOf("ack:1"), session.calls.filter { it.startsWith("ack") })
  }

  @Test
  fun `a stop between the read and the first event commits nothing and handles nothing`() {
    lateinit var processor: TestProcessor
    val session =
      FakeSession(
        clock,
        listOf(events("e1", "e2")),
        onExhausted = {},
        onRead = { processor.stop() },
      )
    processor = TestProcessor(serviceOf(session), recordingHandler())

    processor.run()

    assertEquals(emptyList<String>(), processor.handled)
    assertEquals(emptyList<String>(), session.calls.filter { it.startsWith("ack") })
  }

  @Test
  fun `a failing keep-alive after an event ends the run without committing later events`() {
    val session =
      FakeSession(clock, listOf(events("e1", "e2")), failKeepAliveAt = 1, onExhausted = {})
    val processor = TestProcessor(serviceOf(session), recordingHandler())

    processor.run()

    assertEquals(listOf("e1"), processor.handled)
    assertEquals(listOf("ack:1"), session.calls.filter { it.startsWith("ack") })
    assertEquals("close", session.calls.last())
    // the next scheduled run must not start with a leftover interrupt
    assertFalse(Thread.currentThread().isInterrupted)
  }

  @Test
  fun `a keep-alive that fails while an event is in flight leaves that event uncommitted`() {
    lateinit var processor: TestProcessor
    val first =
      FakeSession(clock, listOf(events("e1", "e2")), failKeepAliveAt = 2, onExhausted = {})
    val second = FakeSession(clock, listOf(events("e2")), onExhausted = { processor.stop() })
    var failedOnce = false
    processor =
      TestProcessor(serviceOf(first, second)) { p, e ->
        if (e.id == "e2" && !failedOnce) {
          failedOnce = true
          // a retry wait in the handler calls keepAlive(), which fails and interrupts the loop
          p.callKeepAlive()
        } else {
          p.handled += e.id
        }
      }

    processor.run()

    // keepAlive #1 ran after e1; keepAlive #2 is the failing call made inside the handler for e2
    assertEquals(listOf("ack:1"), first.calls.filter { it.startsWith("ack") })
    assertFalse(Thread.currentThread().isInterrupted)

    processor.run()

    assertEquals(listOf("e1", "e2"), processor.handled)
    assertEquals(listOf("ack:1", "ack:batch"), second.calls.filter { it.startsWith("ack") })
  }
}
