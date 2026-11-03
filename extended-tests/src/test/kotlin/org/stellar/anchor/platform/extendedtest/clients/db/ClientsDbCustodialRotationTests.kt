package org.stellar.anchor.platform.extendedtest.clients.db

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.client.Sep10Client
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.OkHttpUtil
import org.stellar.sdk.KeyPair

/**
 * The owner key of a custodial client must not depend on the signing key that authenticated. It
 * needs the DB-backed client registry (`clients.type=db`): only then does SEP-10 resolve a key
 * added through the management API to the client name.
 */
class ClientsDbCustodialRotationTests : IntegrationTestBase(TestConfig()) {
  private val gson = GsonUtils.getInstance()
  private val httpClient = OkHttpClient.Builder().build()
  private val clientsUrl = "${config.env["platform.server.url"]}/clients"
  private val webAuthDomain = toml.getString("WEB_AUTH_ENDPOINT").split("/")[2]

  private fun authenticate(keypair: KeyPair): String =
    Sep10Client(
        toml.getString("WEB_AUTH_ENDPOINT"),
        toml.getString("SIGNING_KEY"),
        keypair.accountId,
        String(keypair.secretSeed),
      )
      .auth(webAuthDomain)

  private fun sep12Client(jwt: String) = Sep12Client(toml.getString("KYC_SERVER"), jwt)

  private fun code(request: Request) = httpClient.newCall(request).execute().use { it.code }

  @Test
  fun `test a custodial client keeps its SEP-12 customer after rotating its signing key`() {
    val name = "clientsdb-rot-${KeyPair.random().accountId.takeLast(10)}"
    val firstKey = KeyPair.random()
    val secondKey = KeyPair.random()

    try {
      assertEquals(
        200,
        code(
          OkHttpUtil.buildJsonPutRequest(
            "$clientsUrl/$name",
            gson.toJson(mapOf("type" to "custodial", "signingKeys" to listOf(firstKey.accountId))),
          )
        ),
      )

      val customer =
        sep12Client(authenticate(firstKey))
          .putCustomer(gson.fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java))!!

      assertEquals(
        200,
        code(
          Request.Builder()
            .url("$clientsUrl/$name/signing-keys/${secondKey.accountId}")
            .post("".toRequestBody(null))
            .build()
        ),
      )
      assertEquals(
        204,
        code(
          Request.Builder()
            .url("$clientsUrl/$name/signing-keys/${firstKey.accountId}")
            .delete()
            .build()
        ),
      )

      // Authorized by the owner row alone: the second key never touched the customer before.
      assertNotNull(sep12Client(authenticate(secondKey)).getCustomer(id = customer.id))
    } finally {
      code(Request.Builder().url("$clientsUrl/$name").delete().build())
    }
  }
}
