package org.stellar.anchor.platform.custody

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import java.nio.charset.Charset
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest
import org.stellar.anchor.api.exception.CustodyException
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.platform.apiclient.CustodyApiClient
import org.stellar.anchor.platform.config.CustodyApiConfig
import org.stellar.anchor.util.AuthHeader
import org.stellar.anchor.util.FileUtil.getResourceFileAsString
import org.stellar.anchor.util.GsonUtils

class CustodyApiClientTest {

  companion object {
    private const val AUTH_HEADER_NAME = "testApiKeyName"
    private const val AUTH_HEADER_VALUE = "testApiKeyValue"
    private const val BASE_URL = "http://testBaseUrl.com"
    private const val ASSET_ID = "TEST_ASSET_ID"
  }

  private val gson = GsonUtils.getInstance()

  @MockK(relaxed = true) private lateinit var httpClient: OkHttpClient
  @MockK(relaxed = true) private lateinit var authHelper: AuthHelper
  @MockK(relaxed = true) private lateinit var custodyApiConfig: CustodyApiConfig

  private lateinit var custodyApiClient: CustodyApiClient

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)

    val authHeader = AuthHeader(AUTH_HEADER_NAME, AUTH_HEADER_VALUE)
    every { authHelper.createAuthHeader() } returns authHeader
    every { custodyApiConfig.baseUrl } returns BASE_URL

    custodyApiClient = CustodyApiClient(httpClient, authHelper, custodyApiConfig)
  }

  @Test
  fun test_createCustodyTransaction_success() {
    val response =
      getMockResponse(
        200,
        getResourceFileAsString("custody/api/client/create_custody_transaction_response.json")
      )
    val requestCapture = slot<Request>()
    val call = mockk<Call>()
    val requestJson =
      getResourceFileAsString("custody/api/client/create_custody_transaction_request.json")
    val request = gson.fromJson(requestJson, CreateCustodyTransactionRequest::class.java)

    every { httpClient.newCall(capture(requestCapture)) } returns call
    every { call.execute() } returns response

    custodyApiClient.createTransaction(request)

    Assertions.assertEquals(
      "http://testbaseurl.com/transactions/custody",
      requestCapture.captured.url.toString()
    )
    Assertions.assertEquals("testApiKeyValue", requestCapture.captured.header("testApiKeyName"))
    JSONAssert.assertEquals(
      requestJson,
      requestBodyToString(requestCapture.captured.body),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_createCustodyTransaction_fail_IOException() {
    val call = mockk<Call>()
    val requestJson =
      getResourceFileAsString("custody/api/client/create_custody_transaction_request.json")
    val request = gson.fromJson(requestJson, CreateCustodyTransactionRequest::class.java)

    every { httpClient.newCall(any()) } returns call
    every { call.execute() } throws IOException("Custody IO exception")

    val exception = assertThrows<CustodyException> { custodyApiClient.createTransaction(request) }

    Assertions.assertEquals("Exception occurred during request to Custody API", exception.message)
  }

  @Test
  fun test_createCustodyTransaction_fail_errorStatusCode() {
    val response =
      getMockResponse(400, getResourceFileAsString("custody/api/client/error_response_body.json"))
    val requestCapture = slot<Request>()
    val call = mockk<Call>()
    val requestJson =
      getResourceFileAsString("custody/api/client/create_custody_transaction_request.json")
    val request = gson.fromJson(requestJson, CreateCustodyTransactionRequest::class.java)

    every { httpClient.newCall(capture(requestCapture)) } returns call
    every { call.execute() } returns response

    val exception = assertThrows<CustodyException> { custodyApiClient.createTransaction(request) }

    Assertions.assertEquals(
      """
                  Custody API returned an error. HTTP status[400], response[{
                    "error_code": "12345",
                    "message": "Custody error"
                  }]
                """
        .trimIndent(),
      exception.message
    )
  }

  @Test
  fun test_generateDepositAddress_success() {
    val responseJson =
      getResourceFileAsString("custody/api/client/generate_deposit_address_response.json")
    val response = getMockResponse(200, responseJson)
    val requestCapture = slot<Request>()
    val call = mockk<Call>()

    every { httpClient.newCall(capture(requestCapture)) } returns call
    every { call.execute() } returns response

    val responseAddress = custodyApiClient.generateDepositAddress(ASSET_ID)

    Assertions.assertEquals(
      "http://testbaseurl.com/transactions/payments/assets/TEST_ASSET_ID/address",
      requestCapture.captured.url.toString()
    )
    Assertions.assertEquals("testApiKeyValue", requestCapture.captured.header("testApiKeyName"))

    JSONAssert.assertEquals(responseJson, gson.toJson(responseAddress), JSONCompareMode.STRICT)
  }

  private fun requestBodyToString(requestBody: RequestBody?): String {
    val buffer = Buffer()
    requestBody?.writeTo(buffer)
    return buffer.readString(Charset.forName("UTF-8"))
  }

  private fun getMockResponse(code: Int, responseJson: String): Response {
    return Response.Builder()
      .request(Request.Builder().url("http://test.com").build())
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message("OK")
      .body(responseJson.toResponseBody("application/json".toMediaTypeOrNull()))
      .build()
  }
}
