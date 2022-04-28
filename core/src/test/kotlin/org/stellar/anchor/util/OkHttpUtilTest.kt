package org.stellar.anchor.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.stellar.anchor.util.OkHttpUtil.TYPE_JSON

internal class OkHttpUtilTest {
  companion object {
    val TEST_URL = "https://www.stellar.org"
    val TEST_JSON = "{}"
  }
  @Test
  fun testBuildClient() {
    val client = OkHttpUtil.buildClient()
    assertFalse(client.retryOnConnectionFailure)
  }

  @Test
  fun testBuildJsonPostRequest() {
    val request = OkHttpUtil.buildJsonPostRequest(TEST_URL, TEST_JSON)
    assertEquals("www.stellar.org", request.url.host)
    assertEquals(OkHttpUtil.APPLICATION_JSON_CHARSET_UTF_8, request.header("Content-Type"))
    assertEquals("POST", request.method)
  }

  @Test
  fun testBuildJsonPutRequest() {
    val request = OkHttpUtil.buildJsonPutRequest(TEST_URL, TEST_JSON)
    assertEquals("www.stellar.org", request.url.host)
    assertEquals(OkHttpUtil.APPLICATION_JSON_CHARSET_UTF_8, request.header("Content-Type"))
    assertEquals("PUT", request.method)
  }

  @Test
  fun testBuildGetRequest() {
    val request = OkHttpUtil.buildGetRequest(TEST_URL)
    assertEquals("www.stellar.org", request.url.host)
    assertEquals("GET", request.method)
  }

  @Test
  fun testBuildJsonRequestBody() {
    val requestBody = OkHttpUtil.buildJsonRequestBody(TEST_JSON)
    assertEquals(TYPE_JSON, requestBody.contentType())
  }
}
