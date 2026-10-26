package org.stellar.reference.event

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.application.hooks.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT

@Timeout(60)
class EventRouteLimitsTest {
  private val maxBody = 1_048_576

  // one timestamp per test instance: its string length must not change between two body() calls
  private val timestamp = Instant.now().toString()

  private fun body(idPadding: Int = 0) =
    """{"id":"evt-${"A".repeat(idPadding)}","type":"unsupported_type","timestamp":"$timestamp","payload":{}}"""

  // A valid event body of exactly `size` bytes, padded through the id field.
  private fun bodyOfSize(size: Int): String {
    val padding = size - body().length
    return body(padding).also { assertEquals(size, it.toByteArray().size) }
  }

  // `drainChannel = false` stalls the consumer: events pile up in `channel.send`.
  private fun routeTest(
    drainChannel: Boolean = true,
    bodyReadTimeout: Duration = 10.seconds,
    block: suspend ApplicationTestBuilder.(EventService) -> Unit
  ) = testApplication {
    val service = EventService(retainEvents = true)
    application {
      install(ContentNegotiation) { json() }
      // the production RequestExceptionHandlerPlugin turns handler exceptions into responses
      install(
        createApplicationPlugin("test-call-failed") {
          on(CallFailed) { call, _ -> call.respond(HttpStatusCode.InternalServerError) }
        }
      )
      authentication { basic(AUTH_CONFIG_ENDPOINT) { skipWhen { true } } }
      routing { event(service, enableTestEndpoints = true, bodyReadTimeout = bodyReadTimeout) }
    }
    val drain =
      kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
        if (drainChannel) service.channel.consumeEach {}
      }
    try {
      block(service)
    } finally {
      drain.cancel()
    }
  }

  @Test
  fun `rejects a fixed-length body over 1 MiB with 413 and retains nothing`() =
    routeTest { service ->
      val response =
        client.post("/event") {
          contentType(ContentType.Application.Json)
          setBody(bodyOfSize(maxBody + 1))
        }

      assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
      assertEquals(0, service.getEvents(null).size)
    }

  @Test
  fun `rejects a chunked body growing past 1 MiB with 413 and retains nothing`() =
    routeTest { service ->
      val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
      val response =
        client.post("/event") {
          setBody(
            object : OutgoingContent.WriteChannelContent() {
              override val contentType = ContentType.Application.Json
              // no contentLength: the server cannot rely on a Content-Length header
              override suspend fun writeTo(channel: ByteWriteChannel) {
                repeat(32) { channel.writeFully(chunk) }
              }
            }
          )
        }

      assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
      assertEquals(0, service.getEvents(null).size)
    }

  @Test
  fun `accepts a valid body of exactly 1 MiB and delivers it`() = routeTest { service ->
    val response =
      client.post("/event") {
        contentType(ContentType.Application.Json)
        setBody(bodyOfSize(maxBody))
      }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(1, service.getEvents(null).size)
  }

  @Test
  fun `accepts a typical event and answers with the unchanged body`() = routeTest { service ->
    val response =
      client.post("/event") {
        contentType(ContentType.Application.Json)
        setBody(body())
      }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("""{"code":200,"message":"event processed"}""", response.bodyAsText())
    assertEquals(1, service.getEvents(null).size)
  }

  @Test
  fun `retains at most 32 events when 33 bodies of exactly 1 MiB are posted`() =
    routeTest { service ->
      repeat(33) {
        val response =
          client.post("/event") {
            contentType(ContentType.Application.Json)
            setBody(bodyOfSize(maxBody))
          }
        assertEquals(HttpStatusCode.OK, response.status)
      }

      assertEquals(32, service.getEvents(null).size)
    }

  private suspend fun ApplicationTestBuilder.postEvent(payload: String) =
    client.post("/event") {
      contentType(ContentType.Application.Json)
      setBody(payload)
    }

  @Test
  fun `answers 503 with Retry-After to requests beyond 16 in flight and releases them later`() =
    routeTest(drainChannel = false) { service ->
      coroutineScope {
        val calls = (1..40).map { async(Dispatchers.Default) { postEvent(body()) } }
        // 24 are refused at once; the other 16 stay suspended in channel.send
        withTimeout(10_000) { while (calls.count { it.isCompleted } < 24) delay(10) }
        delay(300)
        val refused = calls.filter { it.isCompleted }.map { it.await() }
        assertEquals(24, refused.size)
        assertEquals(24, refused.count { it.status == HttpStatusCode.ServiceUnavailable })
        assertEquals(24, refused.count { it.headers[HttpHeaders.RetryAfter] == "1" })
        assertEquals(0, service.getEvents(null).size)

        // consume the 16 pending events: their permits come back and the next request is accepted
        val pendingCalls = calls.filter { !it.isCompleted }
        repeat(16) { withTimeout(5_000) { service.channel.receive() } }
        // none of the 24 refused events was delivered
        assertTrue(service.channel.tryReceive().isFailure)
        val accepted = pendingCalls.map { it.await() }
        assertEquals(16, accepted.size)
        assertEquals(16, accepted.count { it.status == HttpStatusCode.OK })
        val next = async(Dispatchers.Default) { postEvent(body()) }
        withTimeout(5_000) { service.channel.receive() }
        assertEquals(HttpStatusCode.OK, next.await().status)
      }
    }

  // Sends a burst of 20 probes. Admitted ones stay suspended in `channel.send` (the consumer is
  // stalled) and never complete, so one 503 proves that all 16 permits are taken.
  private suspend fun CoroutineScope.awaitSaturated(post: suspend () -> HttpResponse) {
    val probes = (1..20).map { async(Dispatchers.Default) { post() } }
    withTimeout(10_000) {
      while (
        probes.none { it.isCompleted && it.await().status == HttpStatusCode.ServiceUnavailable }
      ) delay(10)
    }
  }

  // A body that sends 1 KiB and then never completes: reading it to the end blocks forever.
  private fun stalledBody() =
    object : OutgoingContent.WriteChannelContent() {
      override val contentType = ContentType.Application.Json

      override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeFully(ByteArray(1024) { 'A'.code.toByte() })
        channel.flush()
        awaitCancellation()
      }
    }

  @Test
  fun `refuses with 503 without reading the body once 16 are in flight`() =
    routeTest(drainChannel = false) { _ ->
      coroutineScope {
        awaitSaturated { postEvent(body()) }
        val stalledBody = stalledBody()

        val response = withTimeout(5_000) { client.post("/event") { setBody(stalledBody) } }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        coroutineContext.cancelChildren()
      }
    }

  @Test
  fun `releases the permit after oversized and malformed requests`() = routeTest { service ->
    repeat(20) {
      assertEquals(HttpStatusCode.PayloadTooLarge, postEvent(bodyOfSize(maxBody + 1)).status)
    }
    repeat(20) {
      // invalid timestamp: rejected by the parser with 400 after the permit was taken
      val malformed = body().replace(Regex("\"timestamp\":\"[^\"]*\""), "\"timestamp\":\"nope\"")
      assertEquals(HttpStatusCode.BadRequest, postEvent(malformed).status)
    }

    assertEquals(HttpStatusCode.OK, postEvent(body()).status)
    assertEquals(1, service.getEvents(null).size)
  }

  @Test
  fun `releases the permit when processEvent throws`() = routeTest { service ->
    // sending on a closed channel makes processEvent throw after the permit was taken
    service.channel.close()

    val statuses = (1..20).map { postEvent(body()).status }

    // a leaked permit would turn the 17th answer into 503
    assertEquals(20, statuses.count { it == HttpStatusCode.InternalServerError })
  }
}
