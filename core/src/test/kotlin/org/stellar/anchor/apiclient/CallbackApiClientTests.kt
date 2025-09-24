package org.stellar.anchor.apiclient

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.spyk
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.callback.SendEventRequest
import org.stellar.anchor.api.callback.SendEventRequestPayload
import org.stellar.anchor.auth.AuthHelper

class CallbackApiClientTests {
  lateinit var callbackApiClient: CallbackApiClient
  @MockK(relaxed = true) lateinit var mockAuthHelper: AuthHelper
  @MockK(relaxed = true) lateinit var mockResponseBody: ResponseBody
  @MockK(relaxed = true) lateinit var mockResponse: okhttp3.Response
  @MockK(relaxed = true) lateinit var mockCall: okhttp3.Call
  @MockK(relaxed = true) lateinit var mockClient: OkHttpClient

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    callbackApiClient = spyk(CallbackApiClient(mockAuthHelper, "https://api.example.com"))

    every { callbackApiClient.client } returns mockClient
    every { callbackApiClient.createAuthHeader() } returns null
    every { mockClient.newCall(any()) } returns mockCall
    every { mockCall.execute() } returns mockResponse
    every { mockResponse.body } returns mockResponseBody
    every { mockResponse.code } returns 200
    every { mockResponseBody.string() } returns ""
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      ["", "{}", "non-json message is here", "{'message':'success'}", "{'status':'success'}"]
  )
  fun `test valid responses`(responseBodyString: String) {
    every { mockResponseBody.string() } returns responseBodyString

    val sendEventResponse =
      callbackApiClient.sendEvent(
        SendEventRequest("id", "timestamp", "type", SendEventRequestPayload())
      )
    assert(sendEventResponse.code == 200)
  }
}
