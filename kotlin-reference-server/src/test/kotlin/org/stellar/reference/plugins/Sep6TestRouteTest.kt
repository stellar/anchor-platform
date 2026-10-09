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
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.shared.Customers
import org.stellar.anchor.api.shared.StellarId
import org.stellar.reference.data.Config
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.data.SendEventRequestPayload
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT
import org.stellar.reference.event.processor.Sep6EventProcessor
import org.stellar.reference.service.SepHelper

class Sep6TestRouteTest {
  private val secret = "platform_to_anchor_secret_for_tests_0123456789_abcd"

  private fun platformJwt(key: String = secret) =
    JWT.create()
      .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
      .sign(Algorithm.HMAC256(key))

  private suspend fun ApplicationTestBuilder.skip(id: String, token: String? = platformJwt()) =
    client.post("/sep6/transactions/$id/skip-auto-advance") {
      token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

  // The opt-out registry is process-wide, so every test uses a fresh id.
  private fun newId() = UUID.randomUUID().toString()

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
      routing { testSep6() }
    }
    block()
  }

  private val sepHelper: SepHelper = mockk(relaxed = true)
  private val processor =
    Sep6EventProcessor(
      mockk<Config>(relaxed = true),
      mockk(relaxed = true),
      mockk(relaxed = true),
      mockk(relaxed = true),
      sepHelper,
    )

  private fun statusChanged(id: String) =
    SendEventRequest(
      id = UUID.randomUUID().toString(),
      type = "transaction_status_changed",
      timestamp = "2026-10-01T00:00:00Z",
      payload =
        SendEventRequestPayload(
          transaction =
            GetTransactionResponse.builder()
              .id(id)
              .kind(Kind.DEPOSIT)
              .status(SepTransactionStatus.PENDING_USR_TRANSFER_START)
              .customers(
                Customers.builder()
                  .sender(StellarId.builder().account("GSENDER").build())
                  .receiver(StellarId.builder().account("GSENDER").build())
                  .build()
              )
              .build(),
          quote = null,
          customer = null,
        ),
    )

  @Test
  fun `registering a transaction answers 200 with its id and stops the processor reacting to it`() =
    routeTest {
      val id = newId()

      val response = skip(id)
      processor.onTransactionStatusChanged(statusChanged(id))

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("""{"sessionId":"$id"}""", response.bodyAsText())
      coVerify(exactly = 0) { sepHelper.rpcAction(any(), any()) }
    }

  @Test
  fun `registering the same transaction twice answers 200 both times`() = routeTest {
    val id = newId()

    val first = skip(id)
    val second = skip(id)

    assertEquals(HttpStatusCode.OK, first.status)
    assertEquals(HttpStatusCode.OK, second.status)
  }

  @Test
  fun `registering an id that matches no transaction answers 200`() = routeTest {
    val response = skip("does-not-exist-${newId()}")

    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `a request without credentials is rejected and the processor still reacts`() = routeTest {
    val id = newId()

    val response = skip(id, token = null)
    processor.onTransactionStatusChanged(statusChanged(id))

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    coVerify(atLeast = 1) { sepHelper.rpcAction(any(), any()) }
  }

  @Test
  fun `a token signed with another secret is rejected and the processor still reacts`() =
    routeTest {
      val id = newId()

      val response = skip(id, token = platformJwt("another_secret_for_tests_0123456789_abcdefgh"))
      processor.onTransactionStatusChanged(statusChanged(id))

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      coVerify(atLeast = 1) { sepHelper.rpcAction(any(), any()) }
    }

  @Test
  fun `registering answers 200 without credentials when authentication is disabled`() =
    testApplication {
      val id = newId()
      application {
        install(ContentNegotiation) { json() }
        authentication { basic(AUTH_CONFIG_ENDPOINT) { skipWhen { true } } }
        routing { testSep6() }
      }

      val response = client.post("/sep6/transactions/$id/skip-auto-advance")

      assertEquals(HttpStatusCode.OK, response.status)
    }
}
