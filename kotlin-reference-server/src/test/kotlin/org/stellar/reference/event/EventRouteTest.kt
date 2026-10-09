package org.stellar.reference.event

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT

class EventRouteTest {
  private val maxBody = 1_048_576

  private fun body(idPadding: Int = 0) =
    """{"id":"evt-${"A".repeat(idPadding)}","type":"unsupported_type","timestamp":"${Instant.now()}","payload":{}}"""

  // A valid event body of exactly `size` bytes, padded through the id field.
  private fun bodyOfSize(size: Int): String {
    val padding = size - body().length
    return body(padding).also { assertEquals(size, it.toByteArray().size) }
  }

  private fun routeTest(block: suspend ApplicationTestBuilder.(EventService) -> Unit) =
    testApplication {
      val service = EventService(retainEvents = true)
      application {
        install(ContentNegotiation) { json() }
        authentication { basic(AUTH_CONFIG_ENDPOINT) { skipWhen { true } } }
        routing { event(service, enableTestEndpoints = true) }
      }
      val drain =
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
          service.channel.consumeEach {}
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
}
