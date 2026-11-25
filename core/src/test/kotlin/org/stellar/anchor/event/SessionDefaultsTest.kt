package org.stellar.anchor.event

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.event.AnchorEvent

class SessionDefaultsTest {
  private class RecordingSession : EventService.Session {
    var batchAcks = 0

    override fun publish(event: AnchorEvent) {}

    override fun read(): EventService.ReadResponse = throw UnsupportedOperationException()

    override fun ack(readResponse: EventService.ReadResponse) {
      batchAcks++
    }

    override fun close() {}

    override fun getSessionName() = "recording"
  }

  private fun response(size: Int) =
    object : EventService.ReadResponse {
      private val events: List<AnchorEvent> = List(size) { mockk(relaxed = true) }

      override fun getEvents(): List<AnchorEvent> = events
    }

  @Test
  fun `default ack of fewer events than the response holds commits nothing`() {
    val session = RecordingSession()
    session.ack(response(3), 2)
    assertEquals(0, session.batchAcks)
  }

  @Test
  fun `default ack of every event commits the batch once`() {
    val session = RecordingSession()
    session.ack(response(3), 3)
    assertEquals(1, session.batchAcks)
  }

  @Test
  fun `default ack of more events than the response holds commits the batch once`() {
    val session = RecordingSession()
    session.ack(response(3), 4)
    assertEquals(1, session.batchAcks)
  }

  @Test
  fun `default keepAlive does nothing and does not touch the batch`() {
    val session = RecordingSession()
    session.keepAlive()
    assertEquals(0, session.batchAcks)
  }
}
