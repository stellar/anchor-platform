package org.stellar.anchor.platform.event

import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.InternalServerErrorException
import org.stellar.anchor.event.EventService

/** A queue session that records the calls the event loop makes, for the processor tests. */
internal class RecordingSession(
  private val batches: List<List<AnchorEvent>>,
  private val failAckAt: Int? = null,
  private val failKeepAlive: Boolean = false,
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
    if (failKeepAlive) throw InternalServerErrorException("keep-alive failed")
  }

  override fun close() {
    calls += "close"
  }

  override fun getSessionName() = "recording"
}
