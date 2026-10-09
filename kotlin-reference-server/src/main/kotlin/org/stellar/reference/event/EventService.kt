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
// which exist only when `isTest` is on. A bounded buffer keeps a flood of `POST /event` from
// exhausting the heap.
class EventService(private val retainEvents: Boolean) {
  companion object {
    const val MAX_RETAINED_EVENTS = 10_000
  }

  val channel = Channel<SendEventRequest>()
  private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  private val receivedEvents = ArrayDeque<SendEventRequest>()
  private var evicting = false

  suspend fun processEvent(receivedEvent: SendEventRequest) {
    val instant = Instant.parse(receivedEvent.timestamp)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())

    log.info {
      "Received event ${receivedEvent.id} of type ${receivedEvent.type} at ${dateTime.format(formatter)}"
    }
    channel.send(receivedEvent)
    if (retainEvents) retain(receivedEvent)
  }

  private fun retain(event: SendEventRequest) {
    synchronized(receivedEvents) {
      receivedEvents.addLast(event)
      if (receivedEvents.size > MAX_RETAINED_EVENTS) {
        if (!evicting) {
          evicting = true
          log.warn { "Retained events reached $MAX_RETAINED_EVENTS, evicting the oldest" }
        }
        receivedEvents.removeFirst()
      }
    }
  }

  // Get all events. This is for testing purpose.
  // If txnId is not null, the events are filtered.
  fun getEvents(txnId: String?): List<SendEventRequest> {
    val snapshot = synchronized(receivedEvents) { receivedEvents.toList() }
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
    synchronized(receivedEvents) { receivedEvents.lastOrNull() }

  // Clear all events. This is for testing purpose
  fun clearEvents() {
    log.debug { "Clearing events" }
    synchronized(receivedEvents) {
      receivedEvents.clear()
      evicting = false
    }
  }
}
