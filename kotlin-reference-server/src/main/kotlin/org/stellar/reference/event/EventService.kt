package org.stellar.reference.event

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.channels.Channel
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.log

// Received events are kept only for the test endpoints (`GET /events`, `GET /events/latest`),
// which exist only when `isTest` is on. The buffer is bounded by count and by body bytes, so a
// flood of `POST /event` (padded or not) cannot exhaust the heap.
class EventService(private val retainEvents: Boolean) {
  companion object {
    const val MAX_RETAINED_EVENTS = 10_000
    const val MAX_RETAINED_BYTES = 32L * 1024 * 1024
  }

  val channel = Channel<SendEventRequest>()
  private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  private class Retained(val event: SendEventRequest, val bytes: Int)

  private val receivedEvents = ArrayDeque<Retained>()
  private var retainedBytes = 0L
  private var evicting = false

  // `bodyBytes` is the size of the request body the event was parsed from; it feeds the byte cap.
  suspend fun processEvent(receivedEvent: SendEventRequest, bodyBytes: Int = 0) {
    val instant = Instant.parse(receivedEvent.timestamp)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())

    log.info {
      "Received event ${receivedEvent.id} of type ${receivedEvent.type} at ${dateTime.format(formatter)}"
    }
    channel.send(receivedEvent)
    if (retainEvents) retain(receivedEvent, bodyBytes)
  }

  private fun retain(event: SendEventRequest, bytes: Int) {
    synchronized(receivedEvents) {
      receivedEvents.addLast(Retained(event, bytes))
      retainedBytes += bytes
      while (
        receivedEvents.size > 1 &&
          (receivedEvents.size > MAX_RETAINED_EVENTS || retainedBytes > MAX_RETAINED_BYTES)
      ) {
        if (!evicting) {
          evicting = true
          log.warn { "Retained events reached the cap, evicting the oldest" }
        }
        retainedBytes -= receivedEvents.removeFirst().bytes
      }
    }
  }

  // Get all events. This is for testing purpose.
  // If txnId is not null, the events are filtered.
  fun getEvents(txnId: String?): List<SendEventRequest> {
    val snapshot = synchronized(receivedEvents) { receivedEvents.map { it.event } }
    if (txnId != null) {
      // filter events with txnId
      return snapshot.filter {
        it.type != AnchorEvent.Type.QUOTE_CREATED.type && it.payload.transaction?.id == txnId
      }
    }
    // return all events
    return snapshot
  }

  // Get the latest event recevied. This is for testing purpose
  fun getLatestEvent(): SendEventRequest? =
    synchronized(receivedEvents) { receivedEvents.lastOrNull()?.event }

  // Clear all events. This is for testing purpose
  fun clearEvents() {
    log.debug { "Clearing events" }
    synchronized(receivedEvents) {
      receivedEvents.clear()
      retainedBytes = 0
      evicting = false
    }
  }
}
