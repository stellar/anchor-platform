package org.stellar.anchor.apiclient

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.auth.AuthHelper

class PlatformApiClientGetTransactionTest {
  private lateinit var server: MockWebServer

  @BeforeEach
  fun setup() {
    server = MockWebServer()
    server.start()
  }

  @AfterEach
  fun teardown() {
    server.shutdown()
  }

  private fun client(path: String = "") =
    PlatformApiClient(AuthHelper.forNone(), server.url(path).toString().trimEnd('/'))

  private fun requestedPathFor(id: String, basePath: String = ""): Pair<String, String?> {
    server.enqueue(MockResponse().setBody("{}"))
    client(basePath).getTransaction(id)
    val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
    return recorded.requestUrl!!.encodedPath to recorded.requestUrl!!.query
  }

  @Test
  fun `getTransaction requests the transaction path for a normal id`() {
    val (path, query) = requestedPathFor("9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11")

    assertEquals("/transactions/9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11", path)
    assertNull(query)
  }

  @Test
  fun `getTransaction keeps the endpoint base path`() {
    val (path, _) = requestedPathFor("9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11", "/platform")

    assertEquals("/platform/transactions/9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11", path)
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "",
        ".",
        "..",
        "../clients",
        "../../clients/some-wallet",
        "../transactions?sep=24&page_size=2000000000",
        "../health",
        "not-a-uuid",
        "9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11/../../clients",
        "9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11?x=1",
      ]
  )
  fun `getTransaction rejects anything that is not a transaction id without sending a request`(
    id: String
  ) {
    assertThrows(BadRequestException::class.java) { client().getTransaction(id) }
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `getTransaction rejects a null id without sending a request`() {
    assertThrows(BadRequestException::class.java) { client().getTransaction(null) }
    assertEquals(0, server.requestCount)
  }
}
