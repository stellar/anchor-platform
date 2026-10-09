package org.stellar.anchor.platform.integrationtest

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.reference.client.AnchorReferenceServerClient

/**
 * Pins what [AnchorReferenceServerClient.skipSep6AutoAdvance] puts on the wire and how it reports a
 * failure. The `on_hold` e2e tests rely on this registration to stop racing the reference server: a
 * route that is missing (or a client pointed at the wrong path) must fail loudly, not degrade into
 * the flaky race the registration exists to remove.
 *
 * Needs no running stack.
 */
class AnchorReferenceServerClientRequestTests {
  private lateinit var server: MockWebServer
  private val transactionId = "2a337bba-4280-41ee-8632-2f2752ee2a42"

  @BeforeEach
  fun startServer() {
    server = MockWebServer()
    server.start()
  }

  @AfterEach
  fun stopServer() {
    server.shutdown()
  }

  private fun client() = AnchorReferenceServerClient(Url(server.url("").toString().trimEnd('/')))

  @Test
  fun `skipSep6AutoAdvance posts to the SEP-6 skip-auto-advance route`() {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody("""{"sessionId":"$transactionId"}""")
    )

    runBlocking { client().skipSep6AutoAdvance(transactionId) }

    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/sep6/transactions/$transactionId/skip-auto-advance", request.path)
  }

  @Test
  fun `skipSep6AutoAdvance throws naming the transaction and the status on a non-2xx response`() {
    server.enqueue(MockResponse().setResponseCode(404))

    val ex =
      assertThrows<IllegalStateException> {
        runBlocking { client().skipSep6AutoAdvance(transactionId) }
      }

    assertTrue(ex.message!!.contains(transactionId)) { "message must name the id: ${ex.message}" }
    assertTrue(ex.message!!.contains("404")) { "message must carry the status: ${ex.message}" }
  }
}
