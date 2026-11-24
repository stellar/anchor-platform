package org.stellar.reference.plugins

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
import io.mockk.mockk
import java.util.Date
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.reference.callbacks.test.testCustomer
import org.stellar.reference.data.AuthSettings
import org.stellar.reference.di.configureAuth
import org.stellar.reference.service.sep31.ReceiveService

/**
 * Fails when a test-only route builder registers a route outside
 * `authenticate(AUTH_CONFIG_ENDPOINT)`, so a route added below the wrapper (ANCHOR-1308 ->
 * ANCHOR-1355) cannot silently ship open again. Behind the production verifier, a token signed with
 * the callback secret but without the `callback_api` audience (ANCHOR-1546) is refused as well.
 */
class TestRoutesAuthGuardTest {
  private val secret = "guard_secret_for_tests_0123456789_abcdefgh"

  /** A token signed with the callback secret, built like the platform's, with the given `aud`. */
  private fun token(audience: String?): String {
    val builder = Jwts.builder().expiration(Date(System.currentTimeMillis() + 60_000))
    if (audience != null) builder.audience().add(audience).and()
    return builder.signWith(Keys.hmacShaKeyFor(secret.toByteArray()), Jwts.SIG.HS256).compact()
  }

  private data class Endpoint(val method: HttpMethod, val path: String)

  private fun Route.endpoints(prefix: List<String> = emptyList()): List<Endpoint> {
    val segment =
      when (selector) {
        is HttpMethodRouteSelector,
        is AuthenticationRouteSelector -> null
        else -> selector.toString().takeIf { it.isNotEmpty() && !it.startsWith("(") }
      }
    val path = if (segment != null) prefix + segment else prefix
    val method = (selector as? HttpMethodRouteSelector)?.method
    val own =
      if (method != null) listOf(Endpoint(method, path.joinToString("/", "/"))) else emptyList()
    return own + children.flatMap { it.endpoints(path) }
  }

  private fun assertEveryRouteRequiresAuth(builder: Route.() -> Unit) = testApplication {
    var root: Route? = null
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
      root = routing { builder() }
    }
    startApplication()

    val endpoints = root!!.endpoints()
    assertTrue(endpoints.isNotEmpty(), "No routes were found, the enumeration is broken")
    val credentials =
      mapOf(
        "without credentials" to null,
        "with a token without aud" to token(null),
        "with aud sep10" to token("sep10"),
      )
    endpoints.forEach { endpoint ->
      val path = endpoint.path.replace(Regex("\\{[^}]*}"), UUID.randomUUID().toString())
      credentials.forEach { (description, bearer) ->
        val response =
          client.request(path) {
            method = endpoint.method
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
          }
        assertEquals(
          HttpStatusCode.Unauthorized,
          response.status,
          "${endpoint.method.value} ${endpoint.path} answers $description",
        )
      }
    }
  }

  @Test
  fun `every testSep31 route requires a callback_api token`() = assertEveryRouteRequiresAuth {
    testSep31(mockk<ReceiveService>(relaxed = true))
  }

  @Test
  fun `every testSep6 route requires a callback_api token`() = assertEveryRouteRequiresAuth {
    testSep6()
  }

  @Test
  fun `every testCustomer route requires a callback_api token`() = assertEveryRouteRequiresAuth {
    testCustomer(mockk(relaxed = true))
  }
}
