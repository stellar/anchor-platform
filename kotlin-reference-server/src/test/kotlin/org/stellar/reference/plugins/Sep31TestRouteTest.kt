package org.stellar.reference.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coVerify
import io.mockk.mockk
import java.util.Date
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT
import org.stellar.reference.event.processor.Sep31EventProcessor
import org.stellar.reference.service.sep31.ReceiveService

class Sep31TestRouteTest {
  private val secret = "platform_to_anchor_secret_for_tests_0123456789_abcd"
  private val transactionId = "2a337bba-4280-41ee-8632-2f2752ee2a42"
  private val receiveService: ReceiveService = mockk(relaxed = true)

  private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      install(ContentNegotiation) { json() }
      authentication {
        jwt(AUTH_CONFIG_ENDPOINT) {
          verifier(JWT.require(Algorithm.HMAC256(secret)).build())
          validate { JWTPrincipal(it.payload) }
          challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, "Token is invalid") }
        }
      }
      routing { testSep31(receiveService) }
    }
    block()
  }

  private fun platformJwt(key: String = secret) =
    JWT.create()
      .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
      .sign(Algorithm.HMAC256(key))

  @Test
  fun `process rejects a request without credentials`() = routeTest {
    val response = client.post("/sep31/transactions/$transactionId/process")

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    coVerify(exactly = 0) { receiveService.processReceive(any()) }
  }

  @Test
  fun `process rejects a token signed with another secret`() = routeTest {
    val response =
      client.post("/sep31/transactions/$transactionId/process") {
        header(
          HttpHeaders.Authorization,
          "Bearer ${platformJwt("another_secret_for_tests_0123456789_abcdefgh")}"
        )
      }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    coVerify(exactly = 0) { receiveService.processReceive(any()) }
  }

  @Test
  fun `process accepts the platform token`() = routeTest {
    val response =
      client.post("/sep31/transactions/$transactionId/process") {
        header(HttpHeaders.Authorization, "Bearer ${platformJwt()}")
      }

    assertEquals(HttpStatusCode.OK, response.status)
    coVerify(timeout = 5_000) { receiveService.processReceive(transactionId) }
  }

  // The opt-out registry is process-wide, so every test uses a fresh id.
  private fun newId() = UUID.randomUUID().toString()

  @Test
  fun `skip-auto-advance rejects a request without credentials`() = routeTest {
    val id = newId()

    val response = client.post("/sep31/transactions/$id/skip-auto-advance")

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertFalse(Sep31EventProcessor.isSkippingAutoAdvance(id))
  }

  @Test
  fun `skip-auto-advance rejects a token signed with another secret`() = routeTest {
    val id = newId()

    val response =
      client.post("/sep31/transactions/$id/skip-auto-advance") {
        header(
          HttpHeaders.Authorization,
          "Bearer ${platformJwt("another_secret_for_tests_0123456789_abcdefgh")}"
        )
      }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertFalse(Sep31EventProcessor.isSkippingAutoAdvance(id))
  }

  @Test
  fun `skip-auto-advance accepts the platform token and registers the transaction`() = routeTest {
    val id = newId()

    val response =
      client.post("/sep31/transactions/$id/skip-auto-advance") {
        header(HttpHeaders.Authorization, "Bearer ${platformJwt()}")
      }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("""{"sessionId":"$id"}""", response.bodyAsText())
    assertTrue(Sep31EventProcessor.isSkippingAutoAdvance(id))
  }

  @Test
  fun `skip-auto-advance answers 200 without credentials when authentication is disabled`() =
    testApplication {
      val id = newId()
      application {
        install(ContentNegotiation) { json() }
        authentication { basic(AUTH_CONFIG_ENDPOINT) { skipWhen { true } } }
        routing { testSep31(receiveService) }
      }

      val response = client.post("/sep31/transactions/$id/skip-auto-advance")

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(Sep31EventProcessor.isSkippingAutoAdvance(id))
    }
}
