package org.stellar.anchor.platform.integrationtest

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.OkHttpUtil
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

class CustodialKeyRotationTests : IntegrationTestBase(TestConfig()) {
  private val gson = GsonUtils.getInstance()

  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.MINUTES)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(10, TimeUnit.MINUTES)
      .build()

  private val clientsUrl = "${config.env["platform.server.url"]}/clients"

  private fun authenticate(keyPair: KeyPair): String =
    runBlocking { anchor.auth().authenticate(SigningKeyPair(keyPair)) }.token

  private fun sep12Client(jwt: String) = Sep12Client(toml.getString("KYC_SERVER"), jwt)

  @Test
  fun `test a custodial client keeps its SEP-12 customer after rotating its signing key`() {
    val name = "itest-rot-${KeyPair.random().accountId.takeLast(10)}"
    val firstKey = KeyPair.random()
    val secondKey = KeyPair.random()

    try {
      val created =
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$name",
              gson.toJson(
                mapOf("type" to "custodial", "signingKeys" to listOf(firstKey.accountId))
              ),
            )
          )
          .execute()
      assertEquals(200, created.use { it.code })

      val customer =
        sep12Client(authenticate(firstKey))
          .putCustomer(gson.fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java))!!

      val added =
        httpClient
          .newCall(
            Request.Builder()
              .url("$clientsUrl/$name/signing-keys/${secondKey.accountId}")
              .post("".toRequestBody(null))
              .build()
          )
          .execute()
      assertEquals(200, added.use { it.code })
      val removed =
        httpClient
          .newCall(
            Request.Builder()
              .url("$clientsUrl/$name/signing-keys/${firstKey.accountId}")
              .delete()
              .build()
          )
          .execute()
      assertEquals(204, removed.use { it.code })

      // Authorized by the owner row alone: the second key was never used before the rotation.
      val afterRotation = sep12Client(authenticate(secondKey)).getCustomer(id = customer.id)
      assertNotNull(afterRotation)
    } finally {
      httpClient
        .newCall(Request.Builder().url("$clientsUrl/$name").delete().build())
        .execute()
        .close()
    }
  }
}
