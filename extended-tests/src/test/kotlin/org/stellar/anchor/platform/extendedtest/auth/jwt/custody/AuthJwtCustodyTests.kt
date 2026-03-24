package org.stellar.anchor.platform.extendedtest.auth.jwt.custody

import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest
import org.stellar.anchor.api.exception.CustodyException
import org.stellar.anchor.platform.apiclient.CustodyApiClient
import org.stellar.anchor.platform.config.CustodyApiConfig
import org.stellar.anchor.platform.config.PropertyCustodySecretConfig
import org.stellar.anchor.platform.extendedtest.auth.AbstractAuthIntegrationTest
import org.stellar.anchor.platform.gson
import org.stellar.anchor.util.OkHttpUtil

internal class AuthJwtCustodyTests : AbstractAuthIntegrationTest() {
  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.MINUTES)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(10, TimeUnit.MINUTES)
      .build()

  private val custodyApiConfig: CustodyApiConfig =
    CustodyApiConfig(PropertyCustodySecretConfig()).apply { baseUrl = "http://localhost:8086" }

  private val jwtCustodyClient: CustodyApiClient =
    CustodyApiClient(httpClient, custodyJwtAuthHelper, custodyApiConfig)
  private val jwtWrongKeyCustodyClient: CustodyApiClient =
    CustodyApiClient(httpClient, custodyJwtWrongKeyAuthHelper, custodyApiConfig)
  private val jwtExpiredTokenCustodyClient: CustodyApiClient =
    CustodyApiClient(httpClient, custodyJwtExpiredAuthHelper, custodyApiConfig)

  @Test
  fun `test the custody endpoints with JWT auth`() {
    // Assert the request does not throw a 403.
    // As for the correctness of the request/response, it should be tested in the custody server
    // integration tests.
    jwtCustodyClient.createTransaction(getCustodyDummyRequest())
  }

  @ParameterizedTest
  @CsvSource(value = [POST_CUSTODY_TRANSACTION_ENDPOINT])
  fun `test JWT protection of the custody server`(method: String, endpoint: String) {
    // Check if the request without JWT will cause a 403.
    val httpRequest =
      Request.Builder()
        .url("http://localhost:${CUSTODY_SERVER_SERVER_PORT}${endpoint}")
        .header("Content-Type", "application/json")
        .method(method, getCustodyDummyRequestBody())
        .build()
    val response = httpClient.newCall(httpRequest).execute()
    assertEquals(403, response.code)

    // Check if the wrong JWT key will cause a 403.
    assertThrows<CustodyException> {
      jwtWrongKeyCustodyClient.createTransaction(getCustodyDummyRequest())
    }
    assertThrows<CustodyException> {
      jwtExpiredTokenCustodyClient.createTransaction(getCustodyDummyRequest())
    }
  }

  private fun getCustodyDummyRequest(): CreateCustodyTransactionRequest {
    return CreateCustodyTransactionRequest.builder().id("testId").build()
  }

  private fun getCustodyDummyRequestBody(): RequestBody? {
    return OkHttpUtil.buildJsonRequestBody(gson.toJson(mapOf("id" to "testId")))
  }
}
