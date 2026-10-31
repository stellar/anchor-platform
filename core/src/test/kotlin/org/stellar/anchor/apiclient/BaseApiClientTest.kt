package org.stellar.anchor.apiclient

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.auth.AuthHelper

class BaseApiClientTest {
  private val client = PlatformApiClient(AuthHelper.forNone(), "http://localhost")

  private fun response(code: Int, body: String = "", location: String? = null): Response {
    val builder =
      Response.Builder()
        .request(Request.Builder().url("http://localhost/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status $code")
        .body(body.toResponseBody("application/json".toMediaType()))
    if (location != null) builder.header("Location", location)
    return builder.build()
  }

  @ParameterizedTest
  @ValueSource(ints = [300, 301, 302, 303, 307, 308, 399])
  fun `handleResponse rejects a redirect status`(code: Int) {
    val exception =
      assertThrows<SepException> {
        client.handleResponse(response(code, location = "http://other.example/?token=secret"))
      }

    assertEquals("Unexpected redirect response: HTTP $code", exception.message)
    assertFalse(exception.message!!.contains("secret"))
  }

  @Test
  fun `handleResponse accepts status 299`() {
    assertEquals("""{"ok":true}""", client.handleResponse(response(299, """{"ok":true}""")))
  }

  @ParameterizedTest
  @ValueSource(ints = [400, 401, 500, 503])
  fun `handleResponse keeps the error message of a 4xx or 5xx status`(code: Int) {
    val exception =
      assertThrows<SepException> { client.handleResponse(response(code, "error body $code")) }

    assertEquals("error body $code", exception.message)
  }

  @ParameterizedTest
  @ValueSource(ints = [100, 199])
  fun `handleResponse rejects a status below 200`(code: Int) {
    val exception =
      assertThrows<SepException> { client.handleResponse(response(code, "informational body")) }

    assertEquals("informational body", exception.message)
  }

  @Test
  fun `handleResponse keeps the forbidden and not found mapping`() {
    assertEquals(
      "Forbidden",
      assertThrows<SepNotAuthorizedException> { client.handleResponse(response(403)) }.message,
    )
    assertEquals(
      "Not found",
      assertThrows<SepNotFoundException> { client.handleResponse(response(404)) }.message,
    )
  }
}
