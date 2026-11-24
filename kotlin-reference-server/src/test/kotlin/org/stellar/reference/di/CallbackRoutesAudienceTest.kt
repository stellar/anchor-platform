package org.stellar.reference.di

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.callback.GetCustomerResponse
import org.stellar.anchor.api.callback.GetRateResponse
import org.stellar.reference.callbacks.customer.CustomerService
import org.stellar.reference.callbacks.customer.customer
import org.stellar.reference.callbacks.rate.RateService
import org.stellar.reference.callbacks.rate.rate
import org.stellar.reference.data.AuthSettings
import org.stellar.reference.event.EventService
import org.stellar.reference.event.event

/**
 * ANCHOR-1546 / #4210349: the real `/customer`, `/event` and `/rate` routes, behind the production
 * verifier, accept the platform's `callback_api` token and refuse a token signed with the same
 * secret that has no audience or the `sep10` one.
 */
class CallbackRoutesAudienceTest {
  private val secret = "platform_to_anchor_secret_for_tests_0123456789_abcd"

  private fun token(audience: String?): String {
    val builder = Jwts.builder().expiration(Date(System.currentTimeMillis() + 60_000))
    if (audience != null) builder.audience().add(audience).and()
    return builder.signWith(Keys.hmacShaKeyFor(secret.toByteArray()), Jwts.SIG.HS256).compact()
  }

  private val eventBody =
    """{"id":"evt-1","type":"unsupported_type","timestamp":"${Instant.now()}","payload":{}}"""

  /** The status each route answers to a request carrying [bearer], keyed by `METHOD /path`. */
  private fun statuses(bearer: String): Map<String, HttpStatusCode> {
    val answers = mutableMapOf<String, HttpStatusCode>()
    testApplication {
      val customerService = mockk<CustomerService>()
      coEvery { customerService.getCustomer(any()) } returns GetCustomerResponse()
      val rateService = mockk<RateService>()
      every { rateService.getRate(any()) } returns GetRateResponse()
      val eventService = EventService(retainEvents = true)
      application {
        install(ContentNegotiation) { json() }
        configureAuth(
          AuthSettings(
            AuthSettings.Type.JWT,
            secret,
            "anchor_to_platform_secret_unused_0123456789",
            900_000
          )
        )
        routing {
          customer(customerService)
          rate(rateService)
          event(eventService, enableTestEndpoints = false)
        }
      }
      val drain = CoroutineScope(Dispatchers.Default).launch { eventService.channel.consumeEach {} }
      try {
        val auth = "Bearer $bearer"
        answers["GET /customer"] =
          client.get("/customer") { header(HttpHeaders.Authorization, auth) }.status
        answers["GET /rate"] =
          client.get("/rate") { header(HttpHeaders.Authorization, auth) }.status
        answers["POST /event"] =
          client
            .post("/event") {
              header(HttpHeaders.Authorization, auth)
              contentType(ContentType.Application.Json)
              setBody(eventBody)
            }
            .status
      } finally {
        drain.cancel()
      }
    }
    return answers
  }

  @Test
  fun `every callback route accepts the callback_api token`() {
    statuses(token("callback_api")).forEach { (route, status) ->
      assertNotEquals(HttpStatusCode.Unauthorized, status, "$route refused the platform token")
    }
  }

  @Test
  fun `every callback route refuses a token without audience`() {
    val answers = statuses(token(null))

    assertEquals(setOf("GET /customer", "GET /rate", "POST /event"), answers.keys)
    answers.forEach { (route, status) -> assertEquals(HttpStatusCode.Unauthorized, status, route) }
  }

  @Test
  fun `every callback route refuses a token with the sep10 audience`() {
    statuses(token("sep10")).forEach { (route, status) ->
      assertEquals(HttpStatusCode.Unauthorized, status, route)
    }
  }
}
