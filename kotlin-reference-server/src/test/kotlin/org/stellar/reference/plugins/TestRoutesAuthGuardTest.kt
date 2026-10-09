package org.stellar.reference.plugins

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
import io.mockk.mockk
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.reference.callbacks.test.testCustomer
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT
import org.stellar.reference.service.sep31.ReceiveService

/**
 * Fails when a test-only route builder registers a route outside
 * `authenticate(AUTH_CONFIG_ENDPOINT)`, so a route added below the wrapper (ANCHOR-1308 ->
 * ANCHOR-1355) cannot silently ship open again.
 */
class TestRoutesAuthGuardTest {
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
      authentication {
        jwt(AUTH_CONFIG_ENDPOINT) {
          verifier(
            JWT.require(Algorithm.HMAC256("guard_secret_for_tests_0123456789_abcdefgh")).build()
          )
          validate { JWTPrincipal(it.payload) }
          challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, "Token is invalid") }
        }
      }
      root = routing { builder() }
    }
    startApplication()

    val endpoints = root!!.endpoints()
    assertTrue(endpoints.isNotEmpty(), "No routes were found, the enumeration is broken")
    endpoints.forEach { endpoint ->
      val path = endpoint.path.replace(Regex("\\{[^}]*}"), UUID.randomUUID().toString())
      val response = client.request(path) { method = endpoint.method }
      assertEquals(
        HttpStatusCode.Unauthorized,
        response.status,
        "${endpoint.method.value} ${endpoint.path} answers without credentials",
      )
    }
  }

  @Test
  fun `every testSep31 route rejects a request without credentials`() =
    assertEveryRouteRequiresAuth {
      testSep31(mockk<ReceiveService>(relaxed = true))
    }

  @Test
  fun `every testCustomer route rejects a request without credentials`() =
    assertEveryRouteRequiresAuth {
      testCustomer(mockk(relaxed = true))
    }
}
