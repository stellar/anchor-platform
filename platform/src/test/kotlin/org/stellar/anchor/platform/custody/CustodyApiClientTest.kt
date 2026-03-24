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
import org.apache.http.HttpStatus
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
import org.stellar.anchor.util.GsonUtils

class CustodyApiClientTest {

  companion object {
    private const val AUTH_HEADER_NAME = "testApiKeyName"
    private const val AUTH_HEADER_VALUE = "testApiKeyValue"
    private const val BASE_URL = "http://testBaseUrl.com"
    private const val ASSET_ID = "TEST_ASSET_ID"
    private const val TXN_ID = "1"
    private const val REQUEST_BODY = "{}"
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
  fun test_createTransaction_success() {
    val response = getMockResponse(200, createTransactionResponse)
    val requestCapture = slot<Request>()
    val call = mockk<Call>()
    val request =
      gson.fromJson(createTransactionRequest, CreateCustodyTransactionRequest::class.java)

    every { httpClient.newCall(capture(requestCapture)) } returns call
    every { call.execute() } returns response

    custodyApiClient.createTransaction(request)

    Assertions.assertEquals(
      "http://testbaseurl.com/transactions",
      requestCapture.captured.url.toString()
    )
    Assertions.assertEquals("testApiKeyValue", requestCapture.captured.header("testApiKeyName"))
    JSONAssert.assertEquals(
      createTransactionRequest,
      requestBodyToString(requestCapture.captured.body),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_createTransaction_fail_IOException() {
    val call = mockk<Call>()
    val request =
      gson.fromJson(createTransactionRequest, CreateCustodyTransactionRequest::class.java)

    every { httpClient.newCall(any()) } returns call
    every { call.execute() } throws IOException("Custody IO exception")

    val exception = assertThrows<CustodyException> { custodyApiClient.createTransaction(request) }

    Assertions.assertEquals("Exception occurred during request to Custody API", exception.message)
  }

  @Test
  fun test_createTransaction_fail_errorStatusCode() {
    val response = getMockResponse(400, errorResponseBody)
    val requestCapture = slot<Request>()
    val call = mockk<Call>()
    val request =
      gson.fromJson(createTransactionRequest, CreateCustodyTransactionRequest::class.java)

    every { httpClient.newCall(capture(requestCapture)) } returns call
    every { call.execute() } returns response

    val exception = assertThrows<CustodyException> { custodyApiClient.createTransaction(request) }

    Assertions.assertEquals(
      "Custody API returned an error. HTTP status[400], response[Custody error]",
      exception.message
    )
  }

  @Test
  fun test_generateDepositAddress_success() {
    val response = getMockResponse(200, createTransactionResponse)
    val requestCapture = slot<Request>()
    val call = mockk<Call>()

    every { httpClient.newCall(capture(requestCapture)) } returns call
    every { call.execute() } returns response

    val responseAddress = custodyApiClient.generateDepositAddress(ASSET_ID)

    Assertions.assertEquals(
      "http://testbaseurl.com/assets/TEST_ASSET_ID/addresses",
      requestCapture.captured.url.toString()
    )
    Assertions.assertEquals("testApiKeyValue", requestCapture.captured.header("testApiKeyName"))

    JSONAssert.assertEquals(
      createTransactionResponse,
      gson.toJson(responseAddress),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_createTransactionPayment_success() {
    val response = getMockResponse(200, "{\"id\":\"1\"}")
    val requestCapture = slot<Request>()
    val call = mockk<Call>()

    every { httpClient.newCall(capture(requestCapture)) } returns call
    every { call.execute() } returns response

    custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY)

    Assertions.assertEquals(
      String.format("http://testbaseurl.com/transactions/%s/payments", TXN_ID),
      requestCapture.captured.url.toString()
    )
    Assertions.assertEquals("testApiKeyValue", requestCapture.captured.header("testApiKeyName"))
  }

  @Test
  fun test_createTransactionPayment_fail_IOException() {
    val call = mockk<Call>()
    every { httpClient.newCall(any()) } returns call
    every { call.execute() } throws IOException("Custody IO exception")

    val exception =
      assertThrows<CustodyException> {
        custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY)
      }

    Assertions.assertEquals(HttpStatus.SC_SERVICE_UNAVAILABLE, exception.statusCode)
    Assertions.assertEquals("Exception occurred during request to Custody API", exception.message)
  }

  @Test
  fun test_createCustodyTransaction_fail_errorStatusCode() {
    val response = getMockResponse(429, "{\"rawMessage\":\"Too many requests\"}")
    val requestCapture = slot<Request>()
    val call = mockk<Call>()

    every { httpClient.newCall(capture(requestCapture)) } returns call
    every { call.execute() } returns response

    val exception =
      assertThrows<CustodyException> {
        custodyApiClient.createTransactionPayment(TXN_ID, REQUEST_BODY)
      }

    Assertions.assertEquals(HttpStatus.SC_TOO_MANY_REQUESTS, exception.statusCode)
    Assertions.assertEquals("{\"rawMessage\":\"Too many requests\"}", exception.rawMessage)
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

  private val createTransactionRequest =
    """
{
  "id" : "testId",
  "memo":  "testMemo",
  "memoType": "testMemoType",
  "protocol": "testProtocol",
  "fromAccount": "testFromAccount",
  "toAccount": "testToAccount",
  "amount": "testAmount",
  "asset": "testAmountAsset",
  "kind": "testKind"
}  
"""

  private val createTransactionResponse = """
{
}  
"""

  private val errorResponseBody = """
{
  "rawErrorMessage": "Custody error"
} 
"""

  private val generateDepositAddressResponse =
    """
{
  "address": "testAddress",
  "memo": "testMemo",
  "memoType": "hash"
}
"""
}
