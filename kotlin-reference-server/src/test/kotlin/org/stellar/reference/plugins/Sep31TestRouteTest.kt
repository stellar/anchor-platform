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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT
import org.stellar.reference.event.processor.MAX_AUTO_ADVANCE_OPT_OUTS
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

  private suspend fun ApplicationTestBuilder.skip(id: String) =
    client.post("/sep31/transactions/$id/skip-auto-advance")

  private fun uuid(i: Int) = "00000000-0000-4000-8000-%012x".format(i)

  @BeforeEach fun resetOptOuts() = Sep31EventProcessor.clearAutoAdvanceOptOuts()

  @Test
  fun `skip-auto-advance registers a canonical uuid`() = routeTest {
    val response = skip(transactionId)

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("\"sessionId\":\"$transactionId\""))
    assertTrue(Sep31EventProcessor.isSkippingAutoAdvance(transactionId))
  }

  @Test
  fun `skip-auto-advance accepts an uppercase uuid`() = routeTest {
    val id = transactionId.uppercase()

    assertEquals(HttpStatusCode.OK, skip(id).status)
    assertTrue(Sep31EventProcessor.isSkippingAutoAdvance(id))
  }

  @Test
  fun `skip-auto-advance rejects ids that are not uuids`() = routeTest {
    for (id in listOf("txn-1", "A".repeat(3800), "1-1-1-1-1", "${transactionId}0", "%41%41")) {
      val response = skip(id)

      assertEquals(HttpStatusCode.BadRequest, response.status, id)
      assertTrue(response.bodyAsText().contains("Invalid transactionId: must be a UUID"))
      assertFalse(Sep31EventProcessor.isSkippingAutoAdvance(id), id)
    }
  }

  @Test
  fun `skip-auto-advance answers 429 for a new id once the set is full`() = routeTest {
    repeat(MAX_AUTO_ADVANCE_OPT_OUTS) { assertTrue(Sep31EventProcessor.skipAutoAdvance(uuid(it))) }

    val response = skip(transactionId)

    assertEquals(HttpStatusCode.TooManyRequests, response.status)
    assertTrue(response.bodyAsText().contains("Opt-out limit reached"))
    assertFalse(Sep31EventProcessor.isSkippingAutoAdvance(transactionId))
  }

  @Test
  fun `skip-auto-advance accepts the last free slot and an already registered id when full`() =
    routeTest {
      repeat(MAX_AUTO_ADVANCE_OPT_OUTS - 1) { Sep31EventProcessor.skipAutoAdvance(uuid(it)) }

      assertEquals(HttpStatusCode.OK, skip(transactionId).status)
      assertEquals(HttpStatusCode.OK, skip(transactionId).status)
      assertEquals(HttpStatusCode.OK, skip(uuid(0)).status)
      assertEquals(HttpStatusCode.TooManyRequests, skip(uuid(MAX_AUTO_ADVANCE_OPT_OUTS)).status)
    }
}
