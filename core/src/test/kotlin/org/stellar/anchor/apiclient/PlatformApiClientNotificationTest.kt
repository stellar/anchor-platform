package org.stellar.anchor.apiclient

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.auth.AuthHelper

class PlatformApiClientNotificationTest {
  private lateinit var client: PlatformApiClient

  @BeforeEach
  fun setup() {
    client = spyk(PlatformApiClient(mockk<AuthHelper>(relaxed = true), "http://localhost:8085"))
  }

  private fun respond(code: Int, body: String) {
    every { client.sendRpcRequest(any()) } returns
      Response.Builder()
        .request(Request.Builder().url("http://localhost:8085").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
  }

  private fun sendNotification() {
    client.notifyOnchainFundsReceived("txn-id", "stellar-txn-id", "1", "received")
  }

  @Test
  fun `notification succeeds when the platform returns a result`() {
    respond(200, """[{"jsonrpc":"2.0","id":"1","result":{}}]""")

    assertDoesNotThrow { sendNotification() }
  }

  @Test
  fun `notification throws IOException when the platform returns an HTTP error`() {
    respond(503, "unavailable")

    assertThrows(IOException::class.java) { sendNotification() }
  }

  @Test
  fun `notification throws IOException when the platform reports an internal error`() {
    respond(
      200,
      """[{"jsonrpc":"2.0","id":"1","error":{"code":-32603,"message":"database unavailable"}}]""",
    )

    assertThrows(IOException::class.java) { sendNotification() }
  }

  @Test
  fun `notification throws a non-retryable error when the platform rejects the request`() {
    respond(
      200,
      """[{"jsonrpc":"2.0","id":"1","error":{"code":-32600,"message":"invalid status"}}]""",
    )

    assertThrows(SepException::class.java) { sendNotification() }
  }

  @Test
  fun `notification throws IOException when the platform response cannot be read`() {
    respond(200, "not json")

    assertThrows(IOException::class.java) { sendNotification() }
  }
}
