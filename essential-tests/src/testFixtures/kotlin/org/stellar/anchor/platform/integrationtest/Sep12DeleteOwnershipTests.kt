package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.math.BigInteger
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.platform.integrationtest.Sep12TokenResolutionTests.Companion.muxedToken
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.KeyPair
import org.stellar.sdk.MuxedAccount
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

/**
 * SEP-12 `DELETE /customer/{account}`: only the identity that owns a customer can delete it, and
 * the delete reaches the customer `PUT` stored. Every identity is a fresh keypair, because deleting
 * a customer of the shared wallet account would break the suites that use it.
 */
@Order(RUN_AFTER_PLATFORM_API_TESTS)
class Sep12DeleteOwnershipTests : IntegrationTestBase(TestConfig()) {
  private val sepDomain = config.env["anchor.domain"]!!
  private val kycServer = toml.getString("KYC_SERVER")

  private fun newKeyPair() = SigningKeyPair(KeyPair.random())

  private fun authenticate(keyPair: SigningKeyPair, memoId: ULong? = null): String =
    runBlocking { anchor.auth().authenticate(keyPair, memoId = memoId) }.token

  private fun randomMemoId(): ULong = Random.nextLong(1, Long.MAX_VALUE).toULong()

  private fun putCustomer(jwt: String, memo: String? = null): String {
    val request =
      GsonUtils.getInstance().fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    request.type = "sep24"
    if (memo != null) {
      request.memo = memo
      request.memoType = "id"
    }
    return Sep12Client(kycServer, jwt).putCustomer(request)!!.id
  }

  /** The status of `GET /customer?id=`, which is 404 once the customer is deleted. */
  private fun getStatus(jwt: String, id: String): HttpStatusCode = runBlocking {
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

  /** `DELETE /customer/{account}` with a JSON body; returns the status and the `error` message. */
  private fun delete(
    jwt: String,
    account: String,
    body: String = "{}"
  ): Pair<HttpStatusCode, String?> = runBlocking {
    HttpClient().use { client ->
      val response =
        client.delete("$sepDomain/sep12/customer/$account") {
          headers { bearerAuth(jwt) }
          contentType(ContentType.Application.Json)
          setBody(body)
        }
      val text = response.body<String>()
      val error =
        if (text.isBlank()) null
        else JsonParser.parseString(text).asJsonObject.get("error")?.asString
      response.status to error
    }
  }

  private fun muxedAddress(keyPair: KeyPair, muxedId: Long) =
    MuxedAccount(keyPair.accountId, BigInteger.valueOf(muxedId)).address

  @Test
  fun `test a caller cannot delete another account's customer`() {
    val victim = newKeyPair()
    val victimJwt = authenticate(victim)
    val victimCustomerId = putCustomer(victimJwt)

    val (status, error) = delete(authenticate(newKeyPair()), victim.address)

    assertEquals(HttpStatusCode.Forbidden, status)
    assertEquals("Not authorized to delete account [${victim.address}] with memo [null]", error)
    assertEquals(HttpStatusCode.OK, getStatus(victimJwt, victimCustomerId))
  }

  @Test
  fun `test a caller with a memo cannot delete the customer of another memo of its account`() {
    val keyPair = newKeyPair()
    val memo1 = randomMemoId()
    val memo2 = memo1 + 1uL
    val jwt1 = authenticate(keyPair, memo1)
    val jwt2 = authenticate(keyPair, memo2)
    putCustomer(jwt1)
    val otherMemoCustomerId = putCustomer(jwt2)

    val (status, error) = delete(jwt1, keyPair.address, """{"memo":"$memo2","memo_type":"id"}""")

    assertEquals(HttpStatusCode.Forbidden, status)
    assertEquals("Not authorized to delete account [${keyPair.address}] with memo [$memo2]", error)
    assertEquals(HttpStatusCode.OK, getStatus(jwt2, otherMemoCustomerId))
  }

  @Test
  fun `test the owner deletes its customer`() {
    val keyPair = newKeyPair()
    val jwt = authenticate(keyPair)
    val id = putCustomer(jwt)

    val (status, _) = delete(jwt, keyPair.address)

    assertEquals(HttpStatusCode.OK, status)
    assertEquals(HttpStatusCode.NotFound, getStatus(jwt, id))
  }

  @Test
  fun `test deleting an account with no customer answers not found`() {
    val keyPair = newKeyPair()

    val (status, error) = delete(authenticate(keyPair), keyPair.address)

    assertEquals(HttpStatusCode.NotFound, status)
    assertEquals("User not found.", error)
  }

  @Test
  fun `test a memo-less token deletes the customer of a memo of its account`() {
    val keyPair = newKeyPair()
    val jwt = authenticate(keyPair)
    val memo = randomMemoId().toString()
    val id = putCustomer(jwt, memo)

    val (status, _) = delete(jwt, keyPair.address, """{"memo":"$memo","memo_type":"id"}""")

    assertEquals(HttpStatusCode.OK, status)
    assertEquals(HttpStatusCode.NotFound, getStatus(jwt, id))
  }

  @Test
  fun `test a muxed caller deletes its customer through its muxed address`() {
    val keyPair = KeyPair.random()
    val muxedId = Random.nextLong(1, Long.MAX_VALUE)
    val jwt = muxedToken(config, keyPair, muxedId)
    val id = putCustomer(jwt)

    val (status, _) = delete(jwt, muxedAddress(keyPair, muxedId))

    assertEquals(HttpStatusCode.OK, status)
    assertEquals(HttpStatusCode.NotFound, getStatus(jwt, id))
  }

  @Test
  fun `test a muxed caller deletes its customer through its base account and muxed id`() {
    val keyPair = KeyPair.random()
    val muxedId = Random.nextLong(1, Long.MAX_VALUE)
    val jwt = muxedToken(config, keyPair, muxedId)
    val id = putCustomer(jwt)

    val (status, _) = delete(jwt, keyPair.accountId, """{"memo":"$muxedId","memo_type":"id"}""")

    assertEquals(HttpStatusCode.OK, status)
    assertEquals(HttpStatusCode.NotFound, getStatus(jwt, id))
  }

  @Test
  fun `test a muxed caller cannot delete through its muxed address with another memo`() {
    val keyPair = KeyPair.random()
    val muxedId = Random.nextLong(1, Long.MAX_VALUE)
    val jwt = muxedToken(config, keyPair, muxedId)
    val id = putCustomer(jwt)
    val muxedAddress = muxedAddress(keyPair, muxedId)
    val otherMemo = muxedId - 1

    val (status, error) = delete(jwt, muxedAddress, """{"memo":"$otherMemo","memo_type":"id"}""")

    assertEquals(HttpStatusCode.Forbidden, status)
    assertEquals("Not authorized to delete account [$muxedAddress] with memo [$otherMemo]", error)
    assertEquals(HttpStatusCode.OK, getStatus(jwt, id))
  }

  @Test
  fun `test a memo sent without its type is looked up as an id memo`() {
    val keyPair = newKeyPair()
    val memo = randomMemoId()
    val jwt = authenticate(keyPair, memo)
    val id = putCustomer(jwt)

    val (status, _) = delete(jwt, keyPair.address, """{"memo":"$memo"}""")

    assertEquals(HttpStatusCode.OK, status)
    assertEquals(HttpStatusCode.NotFound, getStatus(jwt, id))
  }

  @Test
  fun `test a memo token deletes its customer whatever memo type it sends`() {
    val keyPair = newKeyPair()
    val memo = randomMemoId()
    val jwt = authenticate(keyPair, memo)
    val id = putCustomer(jwt)

    val (status, _) = delete(jwt, keyPair.address, """{"memo":"$memo","memo_type":"text"}""")

    assertEquals(HttpStatusCode.OK, status)
    assertEquals(HttpStatusCode.NotFound, getStatus(jwt, id))
  }

  // SEP12DO-14
  @Test
  fun `test a memo-less token cannot delete the customer a memo token registered under that memo`() {
    val keyPair = newKeyPair()
    val memo = randomMemoId()
    val memoJwt = authenticate(keyPair, memo)
    val memoLessJwt = authenticate(keyPair)
    val id = putCustomer(memoJwt)

    val (status, error) =
      delete(memoLessJwt, keyPair.address, """{"memo":"$memo","memo_type":"id"}""")

    assertEquals(HttpStatusCode.Forbidden, status)
    assertEquals("not authorized for customer id", error)
    assertEquals(HttpStatusCode.OK, getStatus(memoJwt, id))
  }
}
