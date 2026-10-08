package org.stellar.reference.event

import com.google.gson.Gson
import com.google.gson.JsonParseException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.format.DateTimeParseException
import org.apache.hc.core5.http.HttpStatus
import org.stellar.anchor.api.callback.SendEventResponse
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT

fun Route.event(eventService: EventService, enableTestEndpoints: Boolean) {
  val gson: Gson = GsonUtils.getInstance()

  authenticate(AUTH_CONFIG_ENDPOINT) {
    route("/event") {
      // The `POST /event` endpoint of the Callback API to receive an event.
      post {
        val receivedEvent = parseEvent(gson, call.receive<String>())
        if (receivedEvent.error != null) {
          call.respond(
            HttpStatusCode.BadRequest,
            gson.toJson(SendEventResponse(HttpStatus.SC_BAD_REQUEST, receivedEvent.error)),
          )
          return@post
        }
        eventService.processEvent(receivedEvent.event!!)
        call.respond(gson.toJson(SendEventResponse(HttpStatus.SC_OK, "event processed")))
      }
    }

    if (enableTestEndpoints) {
      route("/events") {
        // Test endpoint to get the events recorded by the reference server.
        // The `txnId` parameter is optional. If it is provided, only the events with the given
        // `txnId`
        // will be returned.
        get { call.respond(gson.toJson(eventService.getEvents(call.parameters["txnId"]))) }
        // Test endpoint to clear the events recorded by the reference server.
        delete {
          eventService.clearEvents()
          call.respond("Events cleared")
        }
      }
      route("/events/latest") {
        // Test endpoint to get the latest event recorded by the reference server.
        get {
          val latestEvent = eventService.getLatestEvent()
          if (latestEvent != null) {
            call.respond(gson.toJson(latestEvent))
          } else {
            call.respond(HttpStatusCode.NotFound)
          }
        }
      }
    }
  }
}

private class ParsedEvent(val event: SendEventRequest?, val error: String?)

// Gson builds the object through reflection and ignores Kotlin's non-null types, so a missing
// field arrives as null. Reject it here instead of letting it crash the event consumer later.
@Suppress("SENSELESS_COMPARISON")
private fun parseEvent(gson: Gson, json: String): ParsedEvent {
  val event =
    try {
      gson.fromJson(json, SendEventRequest::class.java)
    } catch (e: JsonParseException) {
      null
    } ?: return ParsedEvent(null, "Invalid event: malformed JSON")

  val missing =
    when {
      event.id == null -> "id"
      event.type == null -> "type"
      event.timestamp == null -> "timestamp"
      event.payload == null -> "payload"
      else -> null
    }
  if (missing != null) return ParsedEvent(null, "Invalid event: $missing is required")

  try {
    Instant.parse(event.timestamp)
  } catch (e: DateTimeParseException) {
    return ParsedEvent(null, "Invalid event: timestamp must be an ISO-8601 instant")
  }
  return ParsedEvent(event, null)
}
