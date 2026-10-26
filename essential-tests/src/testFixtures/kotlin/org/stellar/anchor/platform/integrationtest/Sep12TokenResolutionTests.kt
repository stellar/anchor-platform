package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerResponse
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MuxedAccount
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

/**
 * SEP-12 `GET /customer` without an `id`: the customer is the one the SEP-10 token identifies.
 * Every test uses identities of its own, because they create customers and the wallet account's
 * customers are shared with other suites.
 */
@Order(RUN_AFTER_PLATFORM_API_TESTS)
class Sep12TokenResolutionTests : IntegrationTestBase(TestConfig()) {
  private val sepDomain = config.env["anchor.domain"]!!
  private val kycServer = toml.getString("KYC_SERVER")

  private fun newKeyPair() = SigningKeyPair(KeyPair.random())

  private fun authenticate(keyPair: SigningKeyPair, memoId: ULong? = null): String =
    runBlocking { anchor.auth().authenticate(keyPair, memoId = memoId) }.token

  private fun randomMemoId(): ULong = Random.nextLong(1, Long.MAX_VALUE).toULong()

  private fun putCustomer(jwt: String): String {
    val request =
      GsonUtils.getInstance().fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    request.type = "sep24"
    return Sep12Client(kycServer, jwt).putCustomer(request)!!.id
  }

  /** The status and body of `GET /customer` with the given query parameters. */
  private fun get(jwt: String, vararg params: Pair<String, String>): Pair<HttpStatusCode, String> =
    runBlocking {
      HttpClient().use { client ->
        val response =
          client.get("$sepDomain/sep12/customer") {
            headers { bearerAuth(jwt) }
            params.forEach { (name, value) -> parameter(name, value) }
          }
        response.status to response.body<String>()
      }
    }

  private fun getCustomer(
    jwt: String,
    vararg params: Pair<String, String>
  ): Sep12GetCustomerResponse {
    val (status, body) = get(jwt, *params)
    assertEquals(HttpStatusCode.OK, status, body)
    return GsonUtils.getInstance().fromJson(body, Sep12GetCustomerResponse::class.java)
  }

  @Test
  fun `test a memo-less token resolves its customer without an id`() {
    val jwt = authenticate(newKeyPair())
    val id = putCustomer(jwt)

    assertEquals(id, getCustomer(jwt, "type" to "sep24").id)
  }

  @Test
  fun `test a token with a memo resolves its customer without an id or a memo`() {
    val jwt = authenticate(newKeyPair(), randomMemoId())
    val id = putCustomer(jwt)

    assertEquals(id, getCustomer(jwt, "type" to "sep24").id)
  }

  @Test
  fun `test a muxed token resolves its customer without an id or a memo`() {
    val jwt = muxedToken(config, KeyPair.random(), Random.nextLong(1, Long.MAX_VALUE))
    val id = putCustomer(jwt)

    assertEquals(id, getCustomer(jwt, "type" to "sep24").id)
  }

  @Test
  fun `test a memo other than the token memo is refused`() {
    val memoId = randomMemoId()
    val jwt = authenticate(newKeyPair(), memoId)
    putCustomer(jwt)

    val (status, body) = get(jwt, "type" to "sep24", "memo" to (memoId + 1uL).toString())

    assertEquals(HttpStatusCode.Forbidden, status)
    assertEquals(
      "The memo specified does not match the memo ID authorized via SEP-10",
      JsonParser.parseString(body).asJsonObject.get("error").asString,
    )
  }

  @Test
  fun `test one account resolves the memo-less customer and the memo customer apart`() {
    val keyPair = newKeyPair()
    val memoLessJwt = authenticate(keyPair)
    val memoJwt = authenticate(keyPair, randomMemoId())
    val memoLessId = putCustomer(memoLessJwt)
    val memoId = putCustomer(memoJwt)

    assertNotEquals(memoLessId, memoId)
    assertEquals(memoLessId, getCustomer(memoLessJwt, "type" to "sep24").id)
    assertEquals(memoId, getCustomer(memoJwt, "type" to "sep24").id)
  }

  @Test
  fun `test a caller that never submitted a customer gets the fields to provide`() {
    val response = getCustomer(authenticate(newKeyPair()), "type" to "sep24")

    assertNull(response.id)
    assertEquals(Sep12Status.NEEDS_INFO, response.status)
    assertTrue(response.fields!!.isNotEmpty())
  }

  // SEP12TK-08
  @Test
  fun `test a memo-less token cannot read by id the customer registered under a memo`() {
    val keyPair = newKeyPair()
    val memoJwt = authenticate(keyPair, randomMemoId())
    val memoLessJwt = authenticate(keyPair)
    val id = putCustomer(memoJwt)

    val (status, body) = get(memoLessJwt, "id" to id, "type" to "sep24")

    assertEquals(HttpStatusCode.Forbidden, status)
    assertEquals(
      "not authorized for customer id",
      JsonParser.parseString(body).asJsonObject.get("error").asString,
    )
    assertEquals(id, getCustomer(memoJwt, "id" to id, "type" to "sep24").id)
  }

  // SEP12TK-09
  @Test
  fun `test a memo token cannot read by id the customer registered without a memo`() {
    val keyPair = newKeyPair()
    val memoLessJwt = authenticate(keyPair)
    val memoJwt = authenticate(keyPair, randomMemoId())
    val id = putCustomer(memoLessJwt)

    val (status, body) = get(memoJwt, "id" to id, "type" to "sep24")

    assertEquals(HttpStatusCode.Forbidden, status)
    assertEquals(
      "not authorized for customer id",
      JsonParser.parseString(body).asJsonObject.get("error").asString,
    )
    assertEquals(id, getCustomer(memoLessJwt, "id" to id, "type" to "sep24").id)
  }

  companion object {
    /**
     * A SEP-10 token whose `sub` is the muxed address of [muxedId] on [keyPair]. AP's own SEP-10
     * refuses a muxed address in the challenge (`KeyPair.fromAccountId`), so it never issues one;
     * the SEP-12 service still accepts one, so the token is signed here with the profile's SEP-10
     * secret.
     */
    fun muxedToken(config: TestConfig, keyPair: KeyPair, muxedId: Long): String {
      val jwtService =
        JwtService(
          config.env["secret.sep6.more_info_url.jwt_secret"],
          config.env["secret.sep10.jwt_secret"]!!,
          config.env["secret.sep45.jwt_secret"]!!,
          config.env["secret.sep24.interactive_url.jwt_secret"]!!,
          config.env["secret.sep24.more_info_url.jwt_secret"]!!,
          config.env["secret.callback_api.auth_secret"]!!,
          config.env["secret.platform_api.auth_secret"]!!,
        )
      val now = Instant.now().epochSecond
      val sub = MuxedAccount(keyPair.accountId, BigInteger.valueOf(muxedId)).address
      return jwtService.encode(
        Sep10Jwt.of(
          "${config.env["anchor.domain"]}/auth",
          sub,
          now,
          now + 3600,
          UUID.randomUUID().toString(),
          null,
          null,
        )
      )
    }
  }
}
