package org.stellar.anchor.util

import okhttp3.Dns
import okhttp3.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.util.OkHttpUtil.TYPE_JSON

internal class OkHttpUtilTest {
  companion object {
    val TEST_URL = "https://www.stellar.org"
    val TEST_JSON = "{}"
  }
  @Test
  fun `test buildClient() ok`() {
    val client = OkHttpUtil.buildClient()
    assertFalse(client.retryOnConnectionFailure)
    assertFalse(client.followRedirects)
    assertFalse(client.followSslRedirects)
    assertEquals(5_000, client.connectTimeoutMillis)
    assertEquals(10_000, client.readTimeoutMillis)
    assertEquals(15_000, client.callTimeoutMillis)
  }

  @Test
  fun `test buildClient for an untrusted host negotiates only HTTP 1_1`() {
    val client = OkHttpUtil.buildClient(Dns.SYSTEM, 1_000)
    assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
    assertFalse(client.protocols.contains(Protocol.HTTP_2))
    assertTrue(client.followRedirects.not())
    assertEquals(1_000, client.callTimeoutMillis)
  }

  @Test
  fun `test buildJsonPostRequest() ok`() {
    val request = OkHttpUtil.buildJsonPostRequest(TEST_URL, TEST_JSON)
    assertEquals("www.stellar.org", request.url.host)
    assertEquals(OkHttpUtil.APPLICATION_JSON_CHARSET_UTF_8, request.header("Content-Type"))
    assertEquals("POST", request.method)
  }

  @Test
  fun `test buildJsonPutRequest() ok`() {
    val request = OkHttpUtil.buildJsonPutRequest(TEST_URL, TEST_JSON)
    assertEquals("www.stellar.org", request.url.host)
    assertEquals(OkHttpUtil.APPLICATION_JSON_CHARSET_UTF_8, request.header("Content-Type"))
    assertEquals("PUT", request.method)
  }

  @Test
  fun `test buildGetRequest() ok`() {
    val request = OkHttpUtil.buildGetRequest(TEST_URL)
    assertEquals("www.stellar.org", request.url.host)
    assertEquals("GET", request.method)
  }

  @Test
  fun `test buildJsonRequestBody() ok`() {
    val requestBody = OkHttpUtil.buildJsonRequestBody(TEST_JSON)
    assertEquals(TYPE_JSON, requestBody.contentType())
  }
}
