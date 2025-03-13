package org.stellar.anchor.platform.integrationtest

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerResponse
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.platform.*
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

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
