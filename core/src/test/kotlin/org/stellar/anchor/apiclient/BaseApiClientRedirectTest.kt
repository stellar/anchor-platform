package org.stellar.anchor.apiclient

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.stellar.anchor.api.callback.SendEventRequest
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.auth.AuthHelper

class BaseApiClientRedirectTest {
  private val apiKey = "api-secret"
  private val transactionId = "9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11"
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

  private fun endpoint() = origin.url("/").toString().trimEnd('/')

  private fun platformCall(): () -> Unit {
    val client = PlatformApiClient(AuthHelper.forApiKey(apiKey), endpoint())
    return { client.getTransaction(transactionId) }
  }

  private fun callbackCall(): () -> Unit {
    val client = CallbackApiClient(AuthHelper.forApiKey(apiKey), endpoint())
    return { client.sendEvent(SendEventRequest()) }
  }

  private fun calls() = listOf(platformCall(), callbackCall())

  @Test
  fun `api clients refuse a cross-origin redirect and send nothing to the target`() {
    for (call in calls()) {
      origin.enqueue(
        MockResponse().setResponseCode(302).setHeader("Location", target.url("/").toString())
      )
      target.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

      assertThrows<SepException> { call() }
    }

    assertEquals(2, origin.requestCount)
    assertEquals(0, target.requestCount)
  }

  @ParameterizedTest
  @CsvSource("302,origin", "301,target", "303,target")
  fun `api clients refuse same-origin and get-converting redirects`(status: Int, location: String) {
    val locationUrl =
      if (location == "origin") origin.url("/elsewhere") else target.url("/elsewhere")
    origin.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          if (request.path == "/elsewhere") MockResponse().setResponseCode(200).setBody("{}")
          else MockResponse().setResponseCode(status).setHeader("Location", locationUrl.toString())
      }
    target.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse().setResponseCode(200).setBody("{}")
      }

    for (call in calls()) {
      assertThrows<SepException> { call() }
    }

    assertEquals(2, origin.requestCount)
    assertEquals(0, target.requestCount)
  }

  @Test
  fun `api clients still send the credential header to the endpoint`() {
    for (call in calls()) {
      origin.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

      call()

      assertEquals(apiKey, origin.takeRequest().getHeader("X-Api-Key"))
    }
  }

  @Test
  fun `api client disables redirects`() {
    for (client in
      listOf<BaseApiClient>(
        PlatformApiClient(AuthHelper.forNone(), endpoint()),
        CallbackApiClient(AuthHelper.forNone(), endpoint()),
      )) {
      assertFalse(client.client.followRedirects)
      assertFalse(client.client.followSslRedirects)
    }
  }

  @Test
  fun `api client keeps its timeouts`() {
    val okHttpClient = PlatformApiClient(AuthHelper.forNone(), endpoint()).client

    assertEquals(tenMinutesMillis, okHttpClient.connectTimeoutMillis)
    assertEquals(tenMinutesMillis, okHttpClient.readTimeoutMillis)
    assertEquals(tenMinutesMillis, okHttpClient.writeTimeoutMillis)
    assertEquals(tenMinutesMillis, okHttpClient.callTimeoutMillis)
  }
}
