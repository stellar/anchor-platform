@file:Suppress("unused")

package org.stellar.anchor.util

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.IOException
import java.net.MalformedURLException
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.util.NetUtil.*

@Execution(SAME_THREAD)
@Order(100)
internal class NetUtilTest {
  @MockK private lateinit var mockCall: okhttp3.Call

  @MockK private lateinit var mockResponse: Response

  @MockK private lateinit var mockResponseBody: ResponseBody

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxed = true)
  }

  @Test
  fun `test fetch successful response`() {
    mockkStatic(NetUtil::class)
    every { NetUtil.getCall(any()) } returns mockCall
    every { mockCall.execute() } returns mockResponse
    every { mockResponse.isSuccessful } returns true
    every { mockResponse.body } returns mockResponseBody
    every { mockResponseBody.string() } returns "result"

    val result = NetUtil.fetch("http://hello")
    assertEquals("result", result)

    verify {
      NetUtil.getCall(any())
      mockCall.execute()
    }

    unmockkStatic(NetUtil::class)
  }

  @Test
  fun `test fetch unsuccessful response`() {
    mockkStatic(NetUtil::class)
    every { NetUtil.getCall(any()) } returns mockCall
    every { mockCall.execute() } returns mockResponse
    every { mockResponse.isSuccessful } returns false

    assertThrows(IOException::class.java) { NetUtil.fetch("http://hello") }
    unmockkStatic(NetUtil::class)
  }

  @Test
  fun `test fetch null response body`() {
    mockkStatic(NetUtil::class)
    every { NetUtil.getCall(any()) } returns mockCall
    every { mockCall.execute() } returns mockResponse
    every { mockResponse.isSuccessful } returns true
    every { mockResponse.body } returns null

    assertThrows(IOException::class.java) { NetUtil.fetch("http://hello") }
    unmockkStatic(NetUtil::class)
  }

  @Test
  fun `test getCall()`() {
    val request = OkHttpUtil.buildGetRequest("https://www.stellar.org")
    assertNotNull(NetUtil.getCall(request))
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "http://www.stellar.org",
        "http://www.stellar.org:8000",
        "https://www.stellar.org/",
        "https://www.stellar.org/.well-known/stellar.toml",
        "https://www.stellar.org/sep1?q=123&p=false",
        "https://www.stellar.org/sep1?q=&p=false",
        "https://www.stellar.org/a/b/c",
        "https://www.stellar.org/a/",
        "http://192.168.100.1",
        "http://192.168.100.1/a/",
        "ftp://ftp.stellar.org",
        "ftp://ftp.stellar.org/a/b/c",
        "ftp://ftp.stellar.org/a/",
        "file:///home/johndoe/a.toml"
      ]
  )
  fun `test valid urls with isUrlValid()`(testValue: String?) {
    assertTrue(isUrlValid(testValue))
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
    strings =
      [
        "http:// www.stellar.org",
        "https:// www.stellar.org",
        "https:// www.stellar.org/a /",
        "https:// www.stellar.org/a?p=123&q= false",
        "https://192.168.100 .1",
        "abc://www.stellar.org",
        "http:// www.stellar.org",
        "http://www.stellar.org:-100",
        "http://www.stellar.org:abc",
        ""
      ]
  )
  fun `test bad urls with isUrlValid()`(testValue: String?) {
    assertFalse(isUrlValid(testValue))
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "www.stellar.org",
        "localhost",
        "127.0.0.1",
        "8.8.8.8",
        "stellar.org",
        "localhost",
        "localhost:8080",
        "127.0.0.1:8080"
      ]
  )
  fun `test valid server port with isServerPortValid`(testValue: String) {
    assertTrue(isServerPortValid(testValue, false))
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
    strings =
      [
        "www1.stellar.org",
        "www .stellar.org",
        "localhost:88080",
        "localhost:-10",
        "localhos",
        "I am not a good host name",
        ""
      ]
  )
  fun `test bad server port with isServerPortValid`(testValue: String?) {
    assertFalse(isServerPortValid(testValue, true))
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "https://test.stellar.org,test.stellar.org",
        "http://test.stellar.org,test.stellar.org",
        "https://test.stellar.org:9800,test.stellar.org:9800",
        "http://test.stellar.org:9800,test.stellar.org:9800",
      ]
  )
  fun `test good URLs for getDomainFromURL`(testUri: String, compareDomain: String) {
    val domain = getDomainFromURL(testUri)
    assertEquals(domain, compareDomain)
  }

  @ParameterizedTest
  @ValueSource(strings = ["bad url", "http :///test.stellar.org:9800/"])
  fun `test bad URLs for getDomainFromURL`(testUrl: String) {
    org.junit.jupiter.api.assertThrows<MalformedURLException> { getDomainFromURL(testUrl) }
  }
}
