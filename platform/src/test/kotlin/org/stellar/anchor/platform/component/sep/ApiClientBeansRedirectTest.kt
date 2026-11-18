package org.stellar.anchor.platform.component.sep

import io.mockk.every
import io.mockk.mockk
import javax.net.ssl.SSLSession
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.callback.GetCustomerRequest
import org.stellar.anchor.api.exception.ServerErrorException
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.platform.callback.RestCustomerIntegration
import org.stellar.anchor.platform.config.CallbackApiConfig
import org.stellar.anchor.util.GsonUtils

class ApiClientBeansRedirectTest {
  private val apiKey = "api-secret"
  private val tenMinutesMillis = 10 * 60 * 1000

  private lateinit var origin: MockWebServer
  private lateinit var target: MockWebServer

  @BeforeEach
  fun setup() {
    origin = MockWebServer()
    target = MockWebServer()
    origin.start()
    target.start()
  }

  @AfterEach
  fun teardown() {
    origin.shutdown()
    target.shutdown()
  }

  private fun httpClient(checkCertificate: Boolean = true): OkHttpClient {
    val config = mockk<CallbackApiConfig>()
    every { config.checkCertificate } returns checkCertificate
    return ApiClientBeans().httpClient(config)
  }

  private fun customerIntegration() =
    RestCustomerIntegration(
      origin.url("/").toString().trimEnd('/'),
      httpClient(),
      AuthHelper.forApiKey(apiKey),
      GsonUtils.getInstance(),
    )

  private val customerRequest = GetCustomerRequest.builder().id("customer-id").build()

  @Test
  fun `callback client refuses a cross-origin redirect and sends nothing to the target`() {
    origin.enqueue(
      MockResponse().setResponseCode(302).setHeader("Location", target.url("/").toString())
    )
    target.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

    val exception =
      assertThrows<ServerErrorException> { customerIntegration().getCustomer(customerRequest) }

    assertEquals("internal server error", exception.message)
    assertEquals(1, origin.requestCount)
    assertEquals(0, target.requestCount)
  }

  @ParameterizedTest
  @CsvSource("302,origin", "301,target", "303,target")
  fun `callback client refuses same-origin and get-converting redirects`(
    status: Int,
    location: String,
  ) {
    val locationUrl =
      if (location == "origin") origin.url("/elsewhere") else target.url("/elsewhere")
    origin.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          if (request.path!!.startsWith("/elsewhere")) {
            MockResponse().setResponseCode(200).setBody("""{"status":"NEEDS_INFO"}""")
          } else {
            MockResponse().setResponseCode(status).setHeader("Location", locationUrl.toString())
          }
      }
    target.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse().setResponseCode(200).setBody("""{"status":"NEEDS_INFO"}""")
      }

    assertThrows<ServerErrorException> { customerIntegration().getCustomer(customerRequest) }

    assertEquals(1, origin.requestCount)
    assertEquals(0, target.requestCount)
  }

  @Test
  fun `callback client still sends the credential header to the endpoint`() {
    origin.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"NEEDS_INFO"}"""))

    customerIntegration().getCustomer(customerRequest)

    assertEquals(apiKey, origin.takeRequest().getHeader("X-Api-Key"))
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `callback client disables redirects in both certificate modes`(checkCertificate: Boolean) {
    val client = httpClient(checkCertificate)

    assertFalse(client.followRedirects)
    assertFalse(client.followSslRedirects)
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `callback client keeps its timeouts and trust-all branch`(checkCertificate: Boolean) {
    val client = httpClient(checkCertificate)

    assertEquals(tenMinutesMillis, client.connectTimeoutMillis)
    assertEquals(tenMinutesMillis, client.readTimeoutMillis)
    assertEquals(tenMinutesMillis, client.writeTimeoutMillis)
    assertEquals(tenMinutesMillis, client.callTimeoutMillis)
    if (!checkCertificate) {
      assertTrue(client.hostnameVerifier.verify("any.example", mockk<SSLSession>(relaxed = true)))
    }
  }
}
