package org.stellar.reference.event

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT

class EventRouteTest {
  private val eventService: EventService = mockk(relaxed = true)

  private val valid =
    """{"id":"e1","type":"transaction_status_changed","timestamp":"2024-01-01T00:00:00Z",""" +
      """"payload":{"transaction":{"id":"t1"}}}"""

  private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      authentication { basic(AUTH_CONFIG_ENDPOINT) { skipWhen { true } } }
      routing { event(eventService, true) }
    }
    block()
  }

  private fun body(vararg fields: String) = "{" + fields.joinToString(",") + "}"

  private val id = """"id":"e1""""
  private val type = """"type":"transaction_status_changed""""
  private val timestamp = """"timestamp":"2024-01-01T00:00:00Z""""
  private val payload = """"payload":{}"""

  private suspend fun ApplicationTestBuilder.post(json: String) =
    client.post("/event") {
      contentType(ContentType.Application.Json)
      setBody(json)
    }

  @Test
  fun `valid event returns 200 and is processed`() = routeTest {
    val response = post(valid)

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("""{"code":200,"message":"event processed"}""", response.bodyAsText())
    coVerify(exactly = 1) { eventService.processEvent(match { it.id == "e1" }) }
  }

  @Test
  fun `payload with no inner fields is accepted`() = routeTest {
    val response = post(body(id, type, timestamp, payload))

    assertEquals(HttpStatusCode.OK, response.status)
    coVerify(exactly = 1) { eventService.processEvent(any()) }
  }

  @Test
  fun `missing payload returns 400 and nothing is processed`() = routeTest {
    val response = post(body(id, type, timestamp))

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(
      """{"code":400,"message":"Invalid event: payload is required"}""",
      response.bodyAsText(),
    )
    coVerify(exactly = 0) { eventService.processEvent(any()) }
  }

  @Test
  fun `null payload returns 400`() = routeTest {
    val response = post(body(id, type, timestamp, """"payload":null"""))

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(
      """{"code":400,"message":"Invalid event: payload is required"}""",
      response.bodyAsText(),
    )
  }

  @Test
  fun `missing id type or timestamp returns 400 naming the field`() = routeTest {
    val cases =
      mapOf(
        body(type, timestamp, payload) to "id",
        body(id, timestamp, payload) to "type",
        body(id, type, payload) to "timestamp",
      )
    for ((json, field) in cases) {
      val response = post(json)
      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals(
        """{"code":400,"message":"Invalid event: $field is required"}""",
        response.bodyAsText(),
      )
    }
    coVerify(exactly = 0) { eventService.processEvent(any()) }
  }

  @Test
  fun `several missing fields report the first in id type timestamp payload order`() = routeTest {
    val response = post("{}")

    assertEquals(
      """{"code":400,"message":"Invalid event: id is required"}""",
      response.bodyAsText(),
    )
  }

  @Test
  fun `unparseable timestamp returns 400`() = routeTest {
    val response = post(body(id, type, """"timestamp":"yesterday"""", payload))

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(
      """{"code":400,"message":"Invalid event: timestamp must be an ISO-8601 instant"}""",
      response.bodyAsText(),
    )
    coVerify(exactly = 0) { eventService.processEvent(any()) }
  }

  @ParameterizedTest
  @ValueSource(strings = ["not json", "null", "{\"id\":", "{\"payload\":\"text\"}"])
  fun `malformed or null body returns 400`(json: String) = routeTest {
    val response = post(json)

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(
      """{"code":400,"message":"Invalid event: malformed JSON"}""",
      response.bodyAsText(),
    )
    coVerify(exactly = 0) { eventService.processEvent(any()) }
  }

  @Test
  fun `timestamp outside LocalDateTime range returns 400`() = routeTest {
    val response = post(body(id, type, """"timestamp":"+1000000000-12-31T23:59:59Z"""", payload))

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(
      """{"code":400,"message":"Invalid event: timestamp must be an ISO-8601 instant"}""",
      response.bodyAsText(),
    )
    coVerify(exactly = 0) { eventService.processEvent(any()) }
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        """"payload":{"transaction":{"id":"t","started_at":"garbage"}}""",
        """"payload":{"transaction":{"id":"t","started_at":{}}}""",
        """"payload":{"transaction":{"id":"t","transfer_received_at":"x"}}""",
        """"payload":{"quote":{"id":"q","expires_at":"garbage"}}""",
      ]
  )
  fun `invalid nested Instant field returns 400 malformed JSON`(payloadField: String) = routeTest {
    val response = post(body(id, type, timestamp, payloadField))

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(
      """{"code":400,"message":"Invalid event: malformed JSON"}""",
      response.bodyAsText(),
    )
    coVerify(exactly = 0) { eventService.processEvent(any()) }
  }

  @Test
  fun `empty body returns 400`() = routeTest {
    val response = post("")

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(
      """{"code":400,"message":"Invalid event: malformed JSON"}""",
      response.bodyAsText(),
    )
  }
}
