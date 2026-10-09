package org.stellar.reference.event

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT

// `POST /event` under `auth.type: JWT`. The `auth.type: NONE` case (200 without credentials) is
// asserted by EventRouteTest, whose harness uses the same `basic { skipWhen { true } }` provider.
class EventRouteAuthTest {
  private val secret = "platform_to_anchor_secret_for_tests_0123456789_abcd"

  private fun routeTest(block: suspend ApplicationTestBuilder.(EventService) -> Unit) =
    testApplication {
      val service = EventService(retainEvents = true)
      application {
        install(ContentNegotiation) { json() }
        authentication {
          jwt(AUTH_CONFIG_ENDPOINT) {
            verifier(JWT.require(Algorithm.HMAC256(secret)).build())
            validate { JWTPrincipal(it.payload) }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, "Token is invalid") }
          }
        }
        routing { event(service, enableTestEndpoints = true) }
      }
      val drain = CoroutineScope(Dispatchers.Default).launch { service.channel.consumeEach {} }
      try {
        block(service)
      } finally {
        drain.cancel()
      }
    }

  private fun platformJwt(key: String = secret) =
    JWT.create()
      .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
      .sign(Algorithm.HMAC256(key))

  private fun body() =
    """{"id":"evt-1","type":"unsupported_type","timestamp":"${Instant.now()}","payload":{}}"""

  @Test
  fun `rejects a request without credentials and retains nothing`() = routeTest { service ->
    val response =
      client.post("/event") {
        contentType(ContentType.Application.Json)
        setBody(body())
      }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertEquals(0, service.getEvents(null).size)
  }

  @Test
  fun `rejects a token signed with another secret`() = routeTest { service ->
    val response =
      client.post("/event") {
        contentType(ContentType.Application.Json)
        header(
          HttpHeaders.Authorization,
          "Bearer ${platformJwt("another_secret_for_tests_0123456789_abcdefgh")}"
        )
        setBody(body())
      }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertEquals(0, service.getEvents(null).size)
  }

  @Test
  fun `accepts the platform token`() = routeTest { service ->
    val response =
      client.post("/event") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer ${platformJwt()}")
        setBody(body())
      }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(1, service.getEvents(null).size)
  }
}
