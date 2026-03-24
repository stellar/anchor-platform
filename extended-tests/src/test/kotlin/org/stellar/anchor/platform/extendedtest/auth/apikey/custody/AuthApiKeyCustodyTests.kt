package org.stellar.anchor.platform.extendedtest.auth.apikey.custody

import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.stellar.anchor.platform.extendedtest.auth.AbstractAuthIntegrationTest
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.OkHttpUtil

// use TEST_PROFILE_NAME = "auth-apikey-custody"
internal class AuthApiKeyCustodyTests : AbstractAuthIntegrationTest() {

  private val gson = GsonUtils.getInstance()
  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.MINUTES)
      .readTimeout(10, TimeUnit.MINUTES)
      .writeTimeout(10, TimeUnit.MINUTES)
      .build()

  @ParameterizedTest
  @CsvSource(value = [POST_CUSTODY_TRANSACTION_ENDPOINT])
  fun test_incomingCustodyAuth_emptyApiKey_authFails(method: String, endpoint: String) {
    val httpRequest =
      Request.Builder()
        .url("http://localhost:$CUSTODY_SERVER_SERVER_PORT$endpoint")
        .header("Content-Type", "application/json")
        .method(method, getCustodyDummyRequestBody())
        .build()
    val response = httpClient.newCall(httpRequest).execute()
    assertEquals(403, response.code)
  }

  @ParameterizedTest
  @CsvSource(value = [POST_CUSTODY_TRANSACTION_ENDPOINT])
  fun test_incomingCustodyAuth_emptyApiKey_authPasses(method: String, endpoint: String) {
    val httpRequest =
      Request.Builder()
        .url("http://localhost:$CUSTODY_SERVER_SERVER_PORT$endpoint")
        .header("Content-Type", "application/json")
        .header("X-Api-Key", PLATFORM_TO_CUSTODY_SECRET)
        .method(method, getCustodyDummyRequestBody())
        .build()
    val response = httpClient.newCall(httpRequest).execute()
    assertEquals(200, response.code)
  }

  private fun getCustodyDummyRequestBody(): RequestBody? {
    return OkHttpUtil.buildJsonRequestBody(gson.toJson(mapOf("id" to "testId")))
  }
}
