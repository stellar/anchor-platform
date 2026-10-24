package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.client.Sep38Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

/**
 * CORS on every SEP endpoint family: the simple response, the preflight, controller errors and the
 * JWT filter's refusal. A browser wallet needs `Access-Control-Allow-Origin` on each of them to
 * read the answer. Every identity is a fresh keypair.
 */
class CorsTests : IntegrationTestBase(TestConfig()) {
  private val domain = config.env["anchor.domain"]!!
  private val origin = "https://wallet.example"

  private fun authenticate(): String =
    runBlocking { anchor.auth().authenticate(SigningKeyPair(KeyPair.random())) }.token

  private fun call(
    method: HttpMethod,
    path: String,
    jwt: String? = null,
    block: HttpRequestBuilder.() -> Unit = {},
  ): HttpResponse = runBlocking {
    HttpClient().use { client ->
      client.request("$domain$path") {
        this.method = method
        header(HttpHeaders.Origin, origin)
        if (jwt != null) header(HttpHeaders.Authorization, "Bearer $jwt")
        block()
      }
    }
  }

  private fun assertAllowsOrigin(response: HttpResponse, what: String) {
    val allowed = response.headers[HttpHeaders.AccessControlAllowOrigin]
    assertTrue(allowed == "*" || allowed == origin) {
      "$what: Access-Control-Allow-Origin was $allowed"
    }
  }

  // SEP1CR-01
  @ParameterizedTest
  @ValueSource(strings = ["/sep6/info", "/sep24/info", "/sep31/info", "/sep38/info"])
  fun `test the info endpoints allow any origin`(path: String) {
    val response = call(HttpMethod.Get, path)

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
  }

  // SEP1CR-02
  @Test
  fun `test the SEP-10 challenge allows the origin`() {
    val response = call(HttpMethod.Get, "/auth?account=${KeyPair.random().accountId}")

    assertEquals(HttpStatusCode.OK, response.status)
    assertAllowsOrigin(response, "GET /auth")
  }

  // SEP1CR-03
  @Test
  fun `test the preflight of every SEP route family allows the method and the Authorization header`() {
    val account = KeyPair.random().accountId
    val routes =
      listOf(
        HttpMethod.Post to "/auth",
        HttpMethod.Get to "/sep6/transactions",
        HttpMethod.Get to "/sep6/deposit",
        HttpMethod.Put to "/sep12/customer",
        HttpMethod.Delete to "/sep12/customer/$account",
        HttpMethod.Post to "/sep24/transactions/deposit/interactive",
        HttpMethod.Post to "/sep31/transactions",
        HttpMethod.Post to "/sep38/quote",
      )

    routes.forEach { (method, path) ->
      val response =
        call(HttpMethod.Options, path) {
          header(HttpHeaders.AccessControlRequestMethod, method.value)
          header(HttpHeaders.AccessControlRequestHeaders, "authorization")
        }
      val what = "preflight ${method.value} $path"
      assertEquals(HttpStatusCode.OK, response.status, what)
      assertAllowsOrigin(response, what)
      assertTrue(response.headers[HttpHeaders.AccessControlAllowMethods]!!.contains(method.value)) {
        "$what: Access-Control-Allow-Methods was ${response.headers[HttpHeaders.AccessControlAllowMethods]}"
      }
      assertTrue(
        response.headers[HttpHeaders.AccessControlAllowHeaders]!!.contains(
          "authorization",
          ignoreCase = true,
        )
      ) {
        "$what: Access-Control-Allow-Headers was ${response.headers[HttpHeaders.AccessControlAllowHeaders]}"
      }
    }
  }

  // SEP1CR-04
  @Test
  fun `test errors the controllers answer allow the origin`() {
    val missingAccount = call(HttpMethod.Get, "/auth")
    assertEquals(HttpStatusCode.BadRequest, missingAccount.status)
    assertAllowsOrigin(missingAccount, "GET /auth without account")

    val unknownQuote = call(HttpMethod.Get, "/sep38/quote/${UUID.randomUUID()}", authenticate())
    assertEquals(HttpStatusCode.NotFound, unknownQuote.status)
    assertAllowsOrigin(unknownQuote, "GET /sep38/quote of an unknown id")
  }

  // SEP1CR-05
  @Test
  fun `test authenticated answers allow the origin`() {
    val jwt = authenticate()
    val quote =
      Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), jwt)
        .postQuote(
          "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
          "10",
          "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        )
    val paths =
      listOf(
        "/sep6/transactions?asset_code=USDC",
        "/sep12/customer?type=sep24",
        "/sep24/transactions?asset_code=USDC",
        "/sep38/quote/${quote.id}",
      )

    paths.forEach { path ->
      val response = call(HttpMethod.Get, path, jwt)
      assertEquals(HttpStatusCode.OK, response.status, path)
      assertAllowsOrigin(response, path)
    }
  }

  // SEP1CR-06
  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "/sep6/transactions",
        "/sep12/customer",
        "/sep24/transactions",
        "/sep31/transactions/abc",
        "/sep38/quote/abc",
      ]
  )
  fun `test the refusal of the JWT filter allows any origin`(path: String) {
    listOf(null, "not-a-jwt").forEach { jwt ->
      val response = call(HttpMethod.Get, path, jwt)
      val what = "$path with ${jwt ?: "no token"}"

      assertEquals(HttpStatusCode.Forbidden, response.status, what)
      assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin], what)
      assertEquals(
        JsonParser.parseString("""{"error":"forbidden"}"""),
        JsonParser.parseString(runBlocking { response.bodyAsText() }),
        what,
      )
    }
  }
}
