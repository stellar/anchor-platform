package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerResponse
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.platform.*
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

@Order(RUN_AFTER_PLATFORM_API_TESTS)
open class Sep12Tests : IntegrationTestBase(TestConfig()) {
  init {
    runBlocking {
      // We have to override the default CLIENT_WALLET_SECRET because the deletion of the customer
      // will fail other tests that uses the same wallet.
      walletKeyPair = SigningKeyPair(KeyPair.random())
      token = anchor.auth().authenticate(walletKeyPair)
    }
  }

  @Test
  fun `test put, get customers`() = runBlocking {
    customerJson
    val customer = Json.decodeFromString<Map<String, String>>(customerJson).toMutableMap()
    customer.remove("email_address")

    // Upload a customer
    printRequest("Calling PUT /customer", customer)
    var pr = anchor.sep12(token).add(customer, type = "sep24")
    printResponse(pr)

    // make sure the customer was uploaded correctly.
    printRequest("Calling GET /customer", customer)
    var gr = anchor.sep12(token).get(pr.id, type = "sep24")
    printResponse(gr)

    assertEquals(pr.id, gr.id)
    assertEquals(Sep12Status.NEEDS_INFO.name, gr.status!!.status)

    customer["email_address"] = "john.doe@stellar.org"

    // Modify the customer
    printRequest("Calling PUT /customer", customer)
    pr = anchor.sep12(token).add(customer, type = "sep31-receiver")
    printResponse(pr)

    // Make sure the customer is modified correctly.
    printRequest("Calling GET /customer", customer)
    gr = anchor.sep12(token).get(pr.id, type = "sep31-receiver")
    printResponse(gr)

    assertEquals(pr.id, gr.id)
    assertEquals(Sep12Status.ACCEPTED.name, gr.status!!.status)

    // Delete the customer
    printRequest("Calling DELETE /customer/$walletKeyPair.address")
    anchor.sep12(token).delete(walletKeyPair.address)

    val ex: ClientRequestException = assertThrows {
      anchor.sep12(token).get(pr.id, type = "sep31-receiver")
    }
  }

  @Test
  fun `test multipart put`() {
    val httpClient = HttpClient()
    runBlocking {
      val putResponse =
        httpClient.put("${config.env["anchor.domain"]!!}/sep12/customer") {
          headers { bearerAuth(token.token) }
          contentType(ContentType.MultiPart.FormData)
          setBody(
            MultiPartFormDataContent(
              formData {
                append("address", "some address")
                append(
                  key = "photo_id_front",
                  value = "value_photo_id_front".toByteArray(),
                  headers =
                    Headers.build {
                      append(HttpHeaders.ContentType, "image/jpeg")
                      append(HttpHeaders.ContentDisposition, "filename=\"photo_id_front.jpg\"")
                    },
                )
                append(
                  key = "photo_id_back",
                  value = "value_photo_id_back".toByteArray(),
                  headers =
                    Headers.build {
                      append(HttpHeaders.ContentType, "image/jpeg")
                      append(HttpHeaders.ContentDisposition, "filename=\"photo_id_back.jpg\"")
                    },
                )
                append(
                  key = "notary_approval_of_photo_id",
                  value = "value_approval_of_photo_id".toByteArray(),
                  headers =
                    Headers.build {
                      append(HttpHeaders.ContentType, "image/jpeg")
                      append(
                        HttpHeaders.ContentDisposition,
                        "filename=\"notary_approval_of_photo_id.jpg\"",
                      )
                    },
                )
                append(
                  key = "photo_proof_residence",
                  value = "value_photo_residence".toByteArray(),
                  headers =
                    Headers.build {
                      append(HttpHeaders.ContentType, "image/jpeg")
                      append(
                        HttpHeaders.ContentDisposition,
                        "filename=\"photo_proof_residence.jpg\"",
                      )
                    },
                )
                append(
                  key = "photo_proof_of_income",
                  value = "value_photo_proof_of_income".toByteArray(),
                  headers =
                    Headers.build {
                      append(HttpHeaders.ContentType, "image/jpeg")
                      append(
                        HttpHeaders.ContentDisposition,
                        "filename=\"photo_proof_of_income.jpg\"",
                      )
                    },
                )
                append(
                  key = "proof_of_liveness",
                  value = "value_proof_of_liveness".toByteArray(),
                  headers =
                    Headers.build {
                      append(HttpHeaders.ContentType, "image/jpeg")
                      append(HttpHeaders.ContentDisposition, "filename=\"proof_of_liveness.jpg\"")
                    },
                )
              }
            )
          )
        }

      val id = gson.fromJson(putResponse.body<String>(), Sep12PutCustomerResponse::class.java).id
      val getResponse = anchor.sep12(token).get(id)

      // Test string fields
      assert(getResponse.providedFields!!.containsKey("address"))

      // Test binary fields
      assert(getResponse.providedFields!!.containsKey("photo_id_front"))
      assert(getResponse.providedFields!!.containsKey("photo_id_back"))
      assert(getResponse.providedFields!!.containsKey("notary_approval_of_photo_id"))
      assert(getResponse.providedFields!!.containsKey("photo_proof_residence"))
      assert(getResponse.providedFields!!.containsKey("photo_proof_of_income"))
      assert(getResponse.providedFields!!.containsKey("proof_of_liveness"))
    }
  }

  @Test
  fun `test cross-account id access is rejected`() = runBlocking {
    val sepDomain = config.env["anchor.domain"]!!

    val victimKeyPair = SigningKeyPair(KeyPair.random())
    val victimToken = anchor.auth().authenticate(victimKeyPair)
    val victimPr =
      anchor
        .sep12(victimToken)
        .add(
          Json.decodeFromString<Map<String, String>>(testCustomer1Json).toMutableMap(),
          type = "sep24",
        )
    val victimCustomerId = victimPr.id

    val attackerKeyPair = SigningKeyPair(KeyPair.random())
    val attackerToken = anchor.auth().authenticate(attackerKeyPair)

    HttpClient().use { httpClient ->
      val getResponse =
        httpClient.get("$sepDomain/sep12/customer") {
          headers { bearerAuth(attackerToken.token) }
          parameter("id", victimCustomerId)
        }
      assertEquals(HttpStatusCode.Forbidden, getResponse.status)

      val putResponse =
        httpClient.put("$sepDomain/sep12/customer") {
          headers { bearerAuth(attackerToken.token) }
          contentType(ContentType.Application.Json)
          setBody("""{"id":"$victimCustomerId","bank_account_number":"attacker-iban"}""")
        }
      assertEquals(HttpStatusCode.Forbidden, putResponse.status)
    }

    val victimGr = anchor.sep12(victimToken).get(victimCustomerId, type = "sep24")
    assert(victimGr.providedFields!!.containsKey("bank_account_number"))
  }

  private fun newToken(keyPair: SigningKeyPair, memoId: ULong? = null): String =
    runBlocking { anchor.auth().authenticate(keyPair, memoId = memoId) }.token

  private fun putSep24Customer(jwt: String): String {
    val request = gson.fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
    request.type = "sep24"
    return Sep12Client(toml.getString("KYC_SERVER"), jwt).putCustomer(request)!!.id
  }

  /** The status, content type and body of a raw `PUT /customer`. */
  private fun putCustomerRaw(jwt: String, body: String) = runBlocking {
    HttpClient().use { client ->
      val response =
        client.put("${config.env["anchor.domain"]!!}/sep12/customer") {
          headers { bearerAuth(jwt) }
          contentType(ContentType.Application.Json)
          setBody(body)
        }
      Triple(response.status, response.contentType(), response.body<String>())
    }
  }

  private fun errorOf(body: String): String =
    JsonParser.parseString(body).asJsonObject.get("error").asString

  // SEP12CV-01
  @Test
  fun `test memos differentiate customers registered by the same account`() {
    val keyPair = SigningKeyPair(KeyPair.random())

    val first = putSep24Customer(newToken(keyPair, 1uL))
    val second = putSep24Customer(newToken(keyPair, 2uL))

    assertTrue(first.isNotEmpty())
    assertTrue(second.isNotEmpty())
    assertNotEquals(first, second)
  }

  // SEP12CV-02
  @Test
  fun `test GET with an account other than the token account is refused`() {
    val jwt = newToken(SigningKeyPair(KeyPair.random()))

    val (status, body) =
      runBlocking {
        HttpClient().use { client ->
          val response =
            client.get("${config.env["anchor.domain"]!!}/sep12/customer") {
              headers { bearerAuth(jwt) }
              parameter("account", KeyPair.random().accountId)
              parameter("type", "sep24")
            }
          response.status to response.body<String>()
        }
      }

    assertEquals(HttpStatusCode.Forbidden, status)
    assertEquals("The account specified does not match authorization token", errorOf(body))
  }

  // SEP12CV-03
  @Test
  fun `test PUT with an account other than the token account is refused and creates nothing`() {
    val jwt = newToken(SigningKeyPair(KeyPair.random()))
    val otherKeyPair = SigningKeyPair(KeyPair.random())

    val (status, _, body) =
      putCustomerRaw(
        jwt,
        """{"account":"${otherKeyPair.address}","first_name":"x","type":"sep24"}""",
      )

    assertEquals(HttpStatusCode.Forbidden, status)
    assertEquals("The account specified does not match authorization token", errorOf(body))
    val other =
      Sep12Client(toml.getString("KYC_SERVER"), newToken(otherKeyPair))
        .getCustomer(type = "sep24")!!
    assertNull(other.id)
    assertEquals(Sep12Status.NEEDS_INFO, other.status)
  }

  // SEP12CV-07
  @Test
  fun `test PUT answers 202 with a JSON body that carries the customer id`() {
    val jwt = newToken(SigningKeyPair(KeyPair.random()))

    val (status, contentType, body) = putCustomerRaw(jwt, testCustomer1Json)

    assertEquals(HttpStatusCode.Accepted, status)
    assertTrue(contentType!!.match(ContentType.Application.Json)) {
      "Content-Type was $contentType"
    }
    val id = JsonParser.parseString(body).asJsonObject.get("id")
    assertTrue(id.isJsonPrimitive && id.asJsonPrimitive.isString && id.asString.isNotEmpty())
  }

  private fun putCustomerOf(jwt: String, fields: Map<String, String>, type: String): String {
    val request =
      gson.fromJson(gson.toJson(fields), Sep12PutCustomerRequest::class.java).also {
        it.type = type
      }
    return Sep12Client(toml.getString("KYC_SERVER"), jwt).putCustomer(request)!!.id
  }

  private fun getCustomerOf(jwt: String, id: String, type: String) =
    Sep12Client(toml.getString("KYC_SERVER"), jwt).getCustomer(id, type)!!

  // SEP12CV-04
  @Test
  fun `test NEEDS_INFO lists the missing required field`() {
    val jwt = newToken(SigningKeyPair(KeyPair.random()))
    val id = putCustomerOf(jwt, mapOf("first_name" to "John", "last_name" to "Doe"), "sep24")

    val customer = getCustomerOf(jwt, id, "sep24")

    assertEquals(Sep12Status.NEEDS_INFO, customer.status)
    assertEquals(false, customer.fields!!["email_address"]!!.optional)
  }

  // SEP12CV-05
  @Test
  fun `test ACCEPTED has no required field left to provide`() {
    val jwt = newToken(SigningKeyPair(KeyPair.random()))
    val id = putCustomerOf(jwt, Json.decodeFromString(testCustomer1Json), "sep24")

    val customer = getCustomerOf(jwt, id, "sep24")

    assertEquals(Sep12Status.ACCEPTED, customer.status)
    assertTrue(customer.fields.orEmpty().values.all { it.optional == true }) {
      "required fields still listed: ${customer.fields}"
    }
  }

  // SEP12CV-06
  @Test
  fun `test the status of one customer depends on the type asked for`() {
    val jwt = newToken(SigningKeyPair(KeyPair.random()))
    val id =
      putCustomerOf(
        jwt,
        mapOf(
          "first_name" to "John",
          "last_name" to "Doe",
          "email_address" to "johndoe@test.com",
        ),
        "sep24",
      )

    val sep24 = getCustomerOf(jwt, id, "sep24")
    val receiver = getCustomerOf(jwt, id, "sep31-receiver")

    assertEquals(Sep12Status.ACCEPTED, sep24.status)
    assertEquals(Sep12Status.NEEDS_INFO, receiver.status)
    assertEquals(false, receiver.fields!!["bank_account_number"]!!.optional)
  }

  // SEP12CV-08
  @Test
  fun `test the TOML KYC_SERVER is the SEP-12 endpoint of the anchor`() {
    val kycServer = toml.getString("KYC_SERVER")

    assertEquals("${config.env["anchor.domain"]!!}/sep12", kycServer)
    assertFalse(kycServer.endsWith("/"))
  }

  companion object {
    const val customerJson =
      """
{
  "first_name": "John",
  "last_name": "Doe",
  "email_address": "johndoe@test.com",
  "address": "123 Washington Street",
  "city": "San Francisco",
  "state_or_province": "CA",
  "address_country_code": "US",
  "clabe_number": "1234",
  "bank_number": "abcd",
  "bank_account_number": "1234",
  "bank_account_type": "checking"
}
"""

    const val testCustomer1Json =
      """
{
  "first_name": "John",
  "last_name": "Doe",
  "email_address": "johndoe@test.com",
  "address": "123 Washington Street",
  "city": "San Francisco",
  "state_or_province": "CA",
  "address_country_code": "US",
  "clabe_number": "1234",
  "bank_number": "abcd",
  "bank_account_number": "1234",
  "bank_account_type": "checking"
}
"""

    const val testCustomer2Json =
      """
{
  "first_name": "Jane",
  "last_name": "Doe",
  "email_address": "janedoe@test.com",
  "address": "321 Washington Street",
  "city": "San Francisco",
  "state_or_province": "CA",
  "address_country_code": "US",
  "clabe_number": "5678",
  "bank_number": "efgh",
  "bank_account_number": "5678",
  "bank_account_type": "checking"
}
"""
  }
}
