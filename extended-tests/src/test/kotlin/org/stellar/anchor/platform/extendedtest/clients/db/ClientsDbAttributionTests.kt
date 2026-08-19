package org.stellar.anchor.platform.extendedtest.clients.db

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.client.Sep10Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.OkHttpUtil
import org.stellar.sdk.KeyPair

class ClientsDbAttributionTests : IntegrationTestBase(TestConfig()) {
  private val gson = GsonUtils.getInstance()
  private val httpClient = OkHttpClient.Builder().build()
  private val clientsUrl = "${config.env["platform.server.url"]}/clients"
  private val webAuthDomain = toml.getString("WEB_AUTH_ENDPOINT").split("/")[2]
  private val jwtService =
    JwtService(null, config.env["secret.sep10.jwt_secret"], null, null, null, null, null)

  private fun uniqueClientName() = "clientsdb-${KeyPair.random().accountId.takeLast(10)}"

  private fun delete(name: String) =
    httpClient.newCall(Request.Builder().url("$clientsUrl/$name").delete().build()).execute()

  private fun authenticate(keypair: KeyPair): Sep10Jwt {
    val jwt =
      Sep10Client(
          toml.getString("WEB_AUTH_ENDPOINT"),
          toml.getString("SIGNING_KEY"),
          keypair.accountId,
          String(keypair.secretSeed),
        )
        .auth(webAuthDomain)
    return jwtService.decode(jwt, Sep10Jwt::class.java)
  }

  @Test
  fun `test SEP-10 attributes client_name to a client created via the DB-backed admin API`() {
    val name = uniqueClientName()
    val keypair = KeyPair.random()
    val createJson =
      gson.toJson(mapOf("type" to "custodial", "signingKeys" to listOf(keypair.accountId)))

    try {
      assertEquals(
        200,
        httpClient
          .newCall(OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", createJson))
          .execute()
          .code,
      )

      assertEquals(name, authenticate(keypair).clientName)
    } finally {
      delete(name)
    }
  }

  @Test
  fun `test SEP-10 client_name is updated after a signing key is moved to another client`() {
    val firstName = uniqueClientName()
    val secondName = uniqueClientName()
    val keypair = KeyPair.random()
    val placeholderKey = KeyPair.random().accountId

    try {
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$firstName",
              gson.toJson(
                mapOf(
                  "type" to "custodial",
                  "signingKeys" to listOf(keypair.accountId, placeholderKey),
                )
              ),
            )
          )
          .execute()
          .code,
      )
      assertEquals(firstName, authenticate(keypair).clientName)

      assertEquals(
        204,
        httpClient
          .newCall(
            Request.Builder()
              .url("$clientsUrl/$firstName/signing-keys/${keypair.accountId}")
              .delete()
              .build()
          )
          .execute()
          .code,
      )
      assertEquals(
        200,
        httpClient
          .newCall(
            OkHttpUtil.buildJsonPutRequest(
              "$clientsUrl/$secondName",
              gson.toJson(mapOf("type" to "custodial", "signingKeys" to listOf(keypair.accountId))),
            )
          )
          .execute()
          .code,
      )

      assertEquals(secondName, authenticate(keypair).clientName)
    } finally {
      delete(firstName)
      delete(secondName)
    }
  }

  @Test
  fun `test SEP-10 leaves client_name unset for a signing key unknown to the DB`() {
    val keypair = KeyPair.random()
    assertNull(authenticate(keypair).clientName)
  }

  @Test
  fun `test SEP-10 no longer attributes client_name after the client is deleted`() {
    val name = uniqueClientName()
    val keypair = KeyPair.random()
    val createJson =
      gson.toJson(mapOf("type" to "custodial", "signingKeys" to listOf(keypair.accountId)))

    assertEquals(
      200,
      httpClient
        .newCall(OkHttpUtil.buildJsonPutRequest("$clientsUrl/$name", createJson))
        .execute()
        .code,
    )
    assertEquals(name, authenticate(keypair).clientName)

    assertEquals(204, delete(name).code)

    assertNull(authenticate(keypair).clientName)
  }
}
