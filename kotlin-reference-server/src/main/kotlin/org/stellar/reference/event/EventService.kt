package org.stellar.reference.event

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.channels.Channel
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.log

class EventService {
  val channel = Channel<SendEventRequest>()
  private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  private val receivedEvents: MutableList<SendEventRequest> = mutableListOf()

  suspend fun processEvent(receivedEvent: SendEventRequest) {
    val instant = Instant.parse(receivedEvent.timestamp)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())

    log.info {
      "Received event ${receivedEvent.id} of type ${receivedEvent.type} at ${dateTime.format(formatter)}"
    }
    channel.send(receivedEvent)
    receivedEvents.add(receivedEvent)
  }

  // Get all events. This is for testing purpose.
  // If txnId is not null, the events are filtered.
  fun getEvents(txnId: String?): List<SendEventRequest> {
    if (txnId != null) {
      // filter events with txnId
      return receivedEvents.filter {
        it.type != AnchorEvent.Type.QUOTE_CREATED.type && it.payload?.transaction?.id == txnId
      }
    }
    // return all events
    return receivedEvents
  }

  // Get the latest event recevied. This is for testing purpose
  fun getLatestEvent(): SendEventRequest? {
    return receivedEvents.lastOrNull()
  }

  // Clear all events. This is for testing purpose
  fun clearEvents() {
    log.debug { "Clearing events" }
    receivedEvents.clear()
  }
}
