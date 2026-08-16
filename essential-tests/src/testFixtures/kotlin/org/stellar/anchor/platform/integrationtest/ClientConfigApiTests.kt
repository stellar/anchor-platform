package org.stellar.anchor.platform.integrationtest

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.OkHttpUtil
import org.stellar.sdk.KeyPair

class ClientConfigApiTests : IntegrationTestBase(TestConfig()) {
  private val gson = GsonUtils.getInstance()

  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.MINUTES)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(10, TimeUnit.MINUTES)
      .build()

  private val clientsUrl = "${config.env["platform.server.url"]}/clients"

  private fun uniqueClientName() = "itest-${KeyPair.random().accountId.takeLast(10)}"

  private fun custodialRequestJson(signingKey: String) =
    gson.toJson(mapOf("type" to "custodial", "signingKeys" to listOf(signingKey)))

  private fun delete(name: String) =
    httpClient.newCall(Request.Builder().url("$clientsUrl/$name").delete().build()).execute()

  @Test
  fun `test create, fetch, list, and delete a client via the REST API`() {
    val name = uniqueClientName()
    val signingKey = KeyPair.random().accountId

    val putResponse =
      httpClient
        .newCall(
          OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", custodialRequestJson(signingKey))
        )
        .execute()
    assertEquals(200, putResponse.code)
    val created = gson.fromJson(putResponse.body!!.string(), HashMap::class.java)
    assertEquals(name, created["name"])
    assertEquals("custodial", created["type"])
    assertEquals(listOf(signingKey), created["signingKeys"])

    val getResponse = httpClient.newCall(OkHttpUtil.buildGetRequest("$clientsUrl/$name")).execute()
    assertEquals(200, getResponse.code)
    val fetched = gson.fromJson(getResponse.body!!.string(), HashMap::class.java)
    assertEquals(name, fetched["name"])
    assertEquals(listOf(signingKey), fetched["signingKeys"])

    val listResponse = httpClient.newCall(OkHttpUtil.buildGetRequest(clientsUrl)).execute()
    assertEquals(200, listResponse.code)
    val allClients = gson.fromJson(listResponse.body!!.string(), List::class.java)
    assertTrue(allClients.any { (it as Map<*, *>)["name"] == name })

    val deleteResponse = delete(name)
    assertEquals(204, deleteResponse.code)

    val getAfterDeleteResponse =
      httpClient.newCall(OkHttpUtil.buildGetRequest("$clientsUrl/$name")).execute()
    assertEquals(404, getAfterDeleteResponse.code)
  }

  @Test
  fun `test fetching an unknown client returns 404`() {
    val response =
      httpClient.newCall(OkHttpUtil.buildGetRequest("$clientsUrl/${uniqueClientName()}")).execute()
    assertEquals(404, response.code)
  }

  @Test
  fun `test deleting an unknown client returns 404`() {
    assertEquals(404, delete(uniqueClientName()).code)
  }

  @Test
  fun `test creating a client with no signing keys is rejected`() {
    val name = uniqueClientName()
    val requestJson =
      gson.toJson(mapOf("type" to "custodial", "signingKeys" to emptyList<String>()))

    val response =
      httpClient.newCall(OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", requestJson)).execute()

    assertEquals(400, response.code)
  }

  @Test
  fun `test creating a client with a signing key already used by another client is rejected`() {
    val signingKey = KeyPair.random().accountId
    val firstName = uniqueClientName()
    val secondName = uniqueClientName()

    try {
      val firstPut =
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$firstName",
              custodialRequestJson(signingKey)
            )
          )
          .execute()
      assertEquals(200, firstPut.code)

      val secondPut =
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$secondName",
              custodialRequestJson(signingKey)
            )
          )
          .execute()
      assertEquals(400, secondPut.code)
    } finally {
      delete(firstName)
      delete(secondName)
    }
  }
}
