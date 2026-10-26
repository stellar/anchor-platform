package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerResponse
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

/**
 * Every SEP-12 endpoint answers a missing or invalid SEP-10 token with `403 {"error":"forbidden"}`
 * and changes nothing.
 */
@Order(RUN_AFTER_PLATFORM_API_TESTS)
class Sep12AuthTests : IntegrationTestBase(TestConfig()) {
  private val sepDomain = config.env["anchor.domain"]!!
  private val kycServer = toml.getString("KYC_SERVER")
  private val gson = GsonUtils.getInstance()

  private fun newKeyPair() = SigningKeyPair(KeyPair.random())

  private fun authenticate(keyPair: SigningKeyPair): String =
    runBlocking { anchor.auth().authenticate(keyPair) }.token

  private fun putCustomer(jwt: String): String {
    val request = gson.fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    request.type = "sep24"
    return Sep12Client(kycServer, jwt).putCustomer(request)!!.id
  }

  private fun assertForbidden(response: Pair<HttpStatusCode, String>) {
    assertEquals(HttpStatusCode.Forbidden, response.first)
    assertEquals(
      "forbidden",
      JsonParser.parseString(response.second).asJsonObject.get("error").asString,
    )
  }

  /** Sends a request with no `Authorization` header when [jwt] is null. */
  private fun call(
    method: HttpMethod,
    path: String,
    jwt: String?,
    body: String? = null,
  ): Pair<HttpStatusCode, String> = runBlocking {
    HttpClient().use { client ->
      val response =
        client.request("$sepDomain/sep12$path") {
          this.method = method
          if (jwt != null) headers { bearerAuth(jwt) }
          if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
          }
        }
      response.status to response.body<String>()
    }
  }

  private fun getById(jwt: String, id: String) = runBlocking {
    HttpClient().use { client ->
      client
        .get("$sepDomain/sep12/customer") {
          headers { bearerAuth(jwt) }
          parameter("id", id)
          parameter("type", "sep24")
        }
        .status
    }
  }

  @Test
  fun `test GET customer without a token is refused`() {
    assertForbidden(call(HttpMethod.Get, "/customer?type=sep24", null))
  }

  @Test
  fun `test GET customer with an invalid token is refused`() {
    assertForbidden(call(HttpMethod.Get, "/customer?type=sep24", "not-a-jwt"))
  }

  @Test
  fun `test PUT customer without a token is refused and creates nothing`() {
    val keyPair = newKeyPair()

    assertForbidden(
      call(
        HttpMethod.Put,
        "/customer",
        null,
        """{"account":"${keyPair.address}","first_name":"x","type":"sep24"}""",
      )
    )

    val (status, body) = call(HttpMethod.Get, "/customer?type=sep24", authenticate(keyPair))
    val customer = gson.fromJson(body, Sep12GetCustomerResponse::class.java)
    assertEquals(HttpStatusCode.OK, status)
    assertEquals(Sep12Status.NEEDS_INFO, customer.status)
    assertNull(customer.id)
  }

  @Test
  fun `test PUT customer with an invalid token is refused`() {
    assertForbidden(call(HttpMethod.Put, "/customer", "not-a-jwt", """{"first_name":"x"}"""))
  }

  @Test
  fun `test DELETE customer without a token is refused and deletes nothing`() {
    val keyPair = newKeyPair()
    val jwt = authenticate(keyPair)
    val id = putCustomer(jwt)

    assertForbidden(call(HttpMethod.Delete, "/customer/${keyPair.address}", null, "{}"))

    assertEquals(HttpStatusCode.OK, getById(jwt, id))
  }

  @Test
  fun `test DELETE customer with an invalid token is refused`() {
    assertForbidden(call(HttpMethod.Delete, "/customer/${newKeyPair().address}", "not-a-jwt", "{}"))
  }
}
