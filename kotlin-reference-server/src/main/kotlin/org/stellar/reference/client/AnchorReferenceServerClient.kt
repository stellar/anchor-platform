package org.stellar.reference.client

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.stellar.anchor.api.callback.SendEventRequest
import org.stellar.anchor.api.callback.SendEventResponse
import org.stellar.anchor.util.GsonUtils

class AnchorReferenceServerClient(val endpoint: Url) {
  val gson: Gson = GsonUtils.getInstance()
  val client = HttpClient()

  suspend fun sendEvent(sendEventRequest: SendEventRequest): SendEventResponse {
    val response =
      client.post {
        url {
          this.protocol = endpoint.protocol
          host = endpoint.host
          port = endpoint.port
          encodedPath = "/event"
        }
        contentType(ContentType.Application.Json)
        setBody(gson.toJson(sendEventRequest))
      }

    return gson.fromJson(response.body<String>(), SendEventResponse::class.java)
  }

  suspend fun getEvents(txnId: String? = null): List<SendEventRequest> {
    val response =
      client.get {
        url {
          this.protocol = endpoint.protocol
          host = endpoint.host
          port = endpoint.port
          encodedPath = "/events"
          txnId?.let { parameter("txnId", it) }
        }
      }

    return gson.fromJson(
      response.body<String>(),
      object : TypeToken<List<SendEventRequest>>() {}.type
    )
  }

  suspend fun pollEvents(txnId: String? = null, expected: Int): List<SendEventRequest> {
    var retries = 5
    var events: List<SendEventRequest> = listOf()
    while (retries > 0) {
      events = getEvents(txnId)
      if (events.size >= expected) {
        return events
      }
      delay(5.seconds)
      retries--
    }
    return events
  }

  suspend fun getLatestEvent(): SendEventRequest? {
    val response =
      client.get {
        url {
          this.protocol = endpoint.protocol
          host = endpoint.host
          port = endpoint.port
          encodedPath = "/events/latest"
        }
      }
    return gson.fromJson(response.body<String>(), SendEventRequest::class.java)
  }

  // Test-only: opts a SEP-31 transaction out of Sep31EventProcessor's automatic advancement, for a
  // test that drives it through RPC calls itself instead (see Sep31TestRoute.kt). Ktor's default
  // HttpClient doesn't throw on a non-2xx response, and the caller relies on this registration to
  // avoid racing the reference server -- checking the status here turns a silent no-op (e.g. the
  // route not being registered) into a clear failure instead of a flaky race down the line.
  suspend fun skipSep31AutoAdvance(transactionId: String) {
    val response =
      client.post {
        url {
          this.protocol = endpoint.protocol
          host = endpoint.host
          port = endpoint.port
          encodedPath = "/sep31/transactions/$transactionId/skip-auto-advance"
        }
      }
    if (!response.status.isSuccess()) {
      throw IllegalStateException(
        "Failed to register transaction($transactionId) to skip auto-advance: " +
          "${response.status}"
      )
    }
  }
}
