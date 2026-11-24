package org.stellar.reference.di

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.util.Date
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * ANCHOR-1546 / #4210349: the callback verifier requires the audience the platform stamps on its
 * tokens, so a token signed with the same secret for another purpose (a SEP-10 token under a reused
 * secret) is not a callback credential. The tokens here are built with jjwt the way the platform's
 * `JwtService.encode(CallbackAuthJwt)` builds them: HS256, `aud` as an array, `exp` set.
 */
class CallbackAuthAudienceTest {
  private val secret = "platform_to_anchor_secret_for_tests_0123456789_abcd"

  private fun token(audience: List<String>?, signedWith: String = secret): String {
    val builder = Jwts.builder().expiration(Date(System.currentTimeMillis() + 60_000))
    if (audience != null) builder.audience().add(audience).and()
    return builder.signWith(Keys.hmacShaKeyFor(signedWith.toByteArray()), Jwts.SIG.HS256).compact()
  }

  private fun statusFor(bearer: String): HttpStatusCode {
    var status = HttpStatusCode.InternalServerError
    testApplication {
      application {
        authentication {
          jwt(AUTH_CONFIG_ENDPOINT) {
            verifier(callbackJwtVerifier(secret))
            validate { JWTPrincipal(it.payload) }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, "Token is invalid") }
          }
        }
        routing { authenticate(AUTH_CONFIG_ENDPOINT) { get("/probe") { call.respondText("ok") } } }
      }
      status = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $bearer") }.status
    }
    return status
  }

  @Test
  fun `accepts a token with the callback_api audience as the platform builds it`() {
    assertEquals(HttpStatusCode.OK, statusFor(token(listOf("callback_api"))))
  }

  @Test
  fun `rejects a token signed with the secret that has no audience`() {
    assertEquals(HttpStatusCode.Unauthorized, statusFor(token(null)))
  }

  @Test
  fun `rejects a token signed with the secret for another audience`() {
    listOf("sep10", "sep24_interactive", "platform_api").forEach { audience ->
      assertEquals(
        HttpStatusCode.Unauthorized,
        statusFor(token(listOf(audience))),
        "aud=$audience",
      )
    }
  }

  @Test
  fun `rejects a token with the callback_api audience signed with another secret`() {
    val other = "another_secret_for_tests_0123456789_abcdefghijkl"

    assertEquals(HttpStatusCode.Unauthorized, statusFor(token(listOf("callback_api"), other)))
  }
}
