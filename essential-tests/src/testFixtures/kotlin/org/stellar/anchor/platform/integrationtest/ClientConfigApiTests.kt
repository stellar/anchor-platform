package org.stellar.anchor.platform.integrationtest

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

  private fun noncustodialRequestJson(domain: String) =
    gson.toJson(mapOf("type" to "noncustodial", "domains" to listOf(domain)))

  private fun delete(name: String) =
    httpClient.newCall(Request.Builder().url("$clientsUrl/$name").delete().build()).execute()

  private fun post(url: String) =
    httpClient.newCall(Request.Builder().url(url).post("".toRequestBody(null)).build()).execute()

  private fun deleteSubResource(url: String) =
    httpClient.newCall(Request.Builder().url(url).delete().build()).execute()

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

  @Test
  fun `test creating a noncustodial client with no domain is rejected`() {
    val name = uniqueClientName()
    val requestJson = gson.toJson(mapOf("type" to "noncustodial", "domains" to emptyList<String>()))

    val response =
      httpClient.newCall(OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", requestJson)).execute()

    assertEquals(400, response.code)
  }

  @Test
  fun `test creating a client with a malformed callback URL is rejected`() {
    val name = uniqueClientName()
    val signingKey = KeyPair.random().accountId
    val requestJson =
      gson.toJson(
        mapOf(
          "type" to "custodial",
          "signingKeys" to listOf(signingKey),
          "callbackUrls" to mapOf("sep24" to "not-a-valid-url"),
        )
      )

    val response =
      httpClient.newCall(OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", requestJson)).execute()

    assertEquals(400, response.code)
  }

  @Test
  fun `test creating a client with an unknown type is rejected`() {
    val name = uniqueClientName()
    val requestJson = gson.toJson(mapOf("type" to "not-a-real-type"))

    val response =
      httpClient.newCall(OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", requestJson)).execute()

    assertEquals(400, response.code)
  }

  @Test
  fun `test creating a client with a domain already used by another client is rejected`() {
    val domain = "itest-${KeyPair.random().accountId.takeLast(10)}.example.com"
    val firstName = uniqueClientName()
    val secondName = uniqueClientName()

    try {
      val firstPut =
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$firstName",
              noncustodialRequestJson(domain)
            )
          )
          .execute()
      assertEquals(200, firstPut.code)

      val secondPut =
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$secondName",
              noncustodialRequestJson(domain)
            )
          )
          .execute()
      assertEquals(400, secondPut.code)
    } finally {
      delete(firstName)
      delete(secondName)
    }
  }

  @Test
  fun `test adding a signing key does not disturb existing ones`() {
    val name = uniqueClientName()
    val firstKey = KeyPair.random().accountId
    val secondKey = KeyPair.random().accountId

    try {
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", custodialRequestJson(firstKey))
          )
          .execute()
          .code
      )

      val addResponse = post("$clientsUrl/$name/signing-keys/$secondKey")
      assertEquals(200, addResponse.code)
      val updated = gson.fromJson(addResponse.body!!.string(), HashMap::class.java)
      assertEquals(setOf(firstKey, secondKey), (updated["signingKeys"] as List<*>).toSet())
    } finally {
      delete(name)
    }
  }

  @Test
  fun `test adding a signing key to an unknown client returns 404`() {
    assertEquals(
      404,
      post("$clientsUrl/${uniqueClientName()}/signing-keys/${KeyPair.random().accountId}").code
    )
  }

  @Test
  fun `test adding a signing key already used by another client is rejected`() {
    val signingKey = KeyPair.random().accountId
    val firstName = uniqueClientName()
    val secondName = uniqueClientName()

    try {
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$firstName",
              custodialRequestJson(signingKey)
            )
          )
          .execute()
          .code
      )
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$secondName",
              custodialRequestJson(KeyPair.random().accountId)
            )
          )
          .execute()
          .code
      )

      val addResponse = post("$clientsUrl/$secondName/signing-keys/$signingKey")
      assertEquals(400, addResponse.code)
    } finally {
      delete(firstName)
      delete(secondName)
    }
  }

  @Test
  fun `test removing a signing key removes only the requested one`() {
    val name = uniqueClientName()
    val firstKey = KeyPair.random().accountId
    val secondKey = KeyPair.random().accountId

    try {
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", custodialRequestJson(firstKey))
          )
          .execute()
          .code
      )
      assertEquals(200, post("$clientsUrl/$name/signing-keys/$secondKey").code)

      val removeResponse = deleteSubResource("$clientsUrl/$name/signing-keys/$secondKey")
      assertEquals(204, removeResponse.code)

      val fetched =
        gson.fromJson(
          httpClient
            .newCall(OkHttpUtil.buildGetRequest("$clientsUrl/$name"))
            .execute()
            .body!!
            .string(),
          HashMap::class.java
        )
      assertEquals(listOf(firstKey), fetched["signingKeys"])
    } finally {
      delete(name)
    }
  }

  @Test
  fun `test removing an unknown signing key returns 404`() {
    val name = uniqueClientName()
    val signingKey = KeyPair.random().accountId
    try {
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", custodialRequestJson(signingKey))
          )
          .execute()
          .code
      )
      assertEquals(
        404,
        deleteSubResource("$clientsUrl/$name/signing-keys/${KeyPair.random().accountId}").code
      )
    } finally {
      delete(name)
    }
  }

  @Test
  fun `test removing the last signing key of a custodial client is rejected`() {
    val name = uniqueClientName()
    val signingKey = KeyPair.random().accountId
    try {
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", custodialRequestJson(signingKey))
          )
          .execute()
          .code
      )

      val removeResponse = deleteSubResource("$clientsUrl/$name/signing-keys/$signingKey")
      assertEquals(400, removeResponse.code)
    } finally {
      delete(name)
    }
  }

  @Test
  fun `test adding and removing a destination account does not disturb existing ones`() {
    val name = uniqueClientName()
    val signingKey = KeyPair.random().accountId
    val firstAccount = KeyPair.random().accountId
    val secondAccount = KeyPair.random().accountId

    try {
      val createJson =
        gson.toJson(
          mapOf(
            "type" to "custodial",
            "signingKeys" to listOf(signingKey),
            "destinationAccounts" to listOf(firstAccount),
          )
        )
      assertEquals(
        200,
        httpClient
          .newCall(OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", createJson))
          .execute()
          .code
      )

      val addResponse = post("$clientsUrl/$name/destination-accounts/$secondAccount")
      assertEquals(200, addResponse.code)
      val afterAdd = gson.fromJson(addResponse.body!!.string(), HashMap::class.java)
      assertEquals(
        setOf(firstAccount, secondAccount),
        (afterAdd["destinationAccounts"] as List<*>).toSet()
      )

      val removeResponse =
        deleteSubResource("$clientsUrl/$name/destination-accounts/$secondAccount")
      assertEquals(204, removeResponse.code)

      val fetched =
        gson.fromJson(
          httpClient
            .newCall(OkHttpUtil.buildGetRequest("$clientsUrl/$name"))
            .execute()
            .body!!
            .string(),
          HashMap::class.java
        )
      assertEquals(listOf(firstAccount), fetched["destinationAccounts"])
    } finally {
      delete(name)
    }
  }

  @Test
  fun `test adding a destination account to an unknown client returns 404`() {
    assertEquals(
      404,
      post("$clientsUrl/${uniqueClientName()}/destination-accounts/${KeyPair.random().accountId}")
        .code
    )
  }

  @Test
  fun `test removing an unknown destination account returns 404`() {
    val name = uniqueClientName()
    val signingKey = KeyPair.random().accountId
    try {
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", custodialRequestJson(signingKey))
          )
          .execute()
          .code
      )
      assertEquals(
        404,
        deleteSubResource("$clientsUrl/$name/destination-accounts/${KeyPair.random().accountId}")
          .code
      )
    } finally {
      delete(name)
    }
  }

  @Test
  fun `test listing custodial and noncustodial clients returns only their own type`() {
    val custodialName = uniqueClientName()
    val noncustodialName = uniqueClientName()
    val domain = "itest-${KeyPair.random().accountId.takeLast(10)}.example.com"

    try {
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$custodialName",
              custodialRequestJson(KeyPair.random().accountId)
            )
          )
          .execute()
          .code
      )
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$noncustodialName",
              noncustodialRequestJson(domain)
            )
          )
          .execute()
          .code
      )

      val custodialResponse =
        httpClient.newCall(OkHttpUtil.buildGetRequest("$clientsUrl/custodial")).execute()
      assertEquals(200, custodialResponse.code)
      val custodialClients = gson.fromJson(custodialResponse.body!!.string(), List::class.java)
      assertTrue(custodialClients.any { (it as Map<*, *>)["name"] == custodialName })
      assertFalse(custodialClients.any { (it as Map<*, *>)["name"] == noncustodialName })
      assertTrue(custodialClients.all { (it as Map<*, *>)["type"] == "custodial" })

      val noncustodialResponse =
        httpClient.newCall(OkHttpUtil.buildGetRequest("$clientsUrl/non-custodial")).execute()
      assertEquals(200, noncustodialResponse.code)
      val noncustodialClients =
        gson.fromJson(noncustodialResponse.body!!.string(), List::class.java)
      assertTrue(noncustodialClients.any { (it as Map<*, *>)["name"] == noncustodialName })
      assertFalse(noncustodialClients.any { (it as Map<*, *>)["name"] == custodialName })
      assertTrue(noncustodialClients.all { (it as Map<*, *>)["type"] == "noncustodial" })
    } finally {
      delete(custodialName)
      delete(noncustodialName)
    }
  }
}
