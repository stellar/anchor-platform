@file:Suppress("unused")

package org.stellar.anchor.util

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.MalformedURLException
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.LockStatic
import org.stellar.anchor.util.NetUtil.*

@Order(100)
@ExtendWith(LockAndMockTest::class)
internal class NetUtilTest {
  @MockK private lateinit var mockCall: okhttp3.Call

  @MockK private lateinit var mockResponse: Response

  @MockK private lateinit var mockResponseBody: ResponseBody

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxed = true)
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `test fetch successful response`() {
    every { getCall(any()) } returns mockCall
    every { mockCall.execute() } returns mockResponse
    every { mockResponse.isSuccessful } returns true
    every { mockResponse.body } returns mockResponseBody
    every { mockResponseBody.contentType() } returns null
    every { mockResponseBody.byteStream() } returns ByteArrayInputStream("result".toByteArray())

    val result = fetch("http://hello")
    assertEquals("result", result)

    verify {
      getCall(any())
      mockCall.execute()
    }
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `test fetch unsuccessful response`() {
    every { getCall(any()) } returns mockCall
    every { mockCall.execute() } returns mockResponse
    every { mockResponse.isSuccessful } returns false

    assertThrows(IOException::class.java) { NetUtil.fetch("http://hello") }
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `test fetch null response body`() {
    every { getCall(any()) } returns mockCall
    every { mockCall.execute() } returns mockResponse
    every { mockResponse.isSuccessful } returns true
    every { mockResponse.body } returns null

    assertThrows(IOException::class.java) { NetUtil.fetch("http://hello") }
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `test fetch oversized response body`() {
    every { getCall(any()) } returns mockCall
    every { mockCall.execute() } returns mockResponse
    every { mockResponse.isSuccessful } returns true
    every { mockResponse.body } returns mockResponseBody
    every { mockResponseBody.contentType() } returns null
    every { mockResponseBody.byteStream() } returns ByteArrayInputStream(ByteArray(100 * 1024 + 1))

    assertThrows(IOException::class.java) { NetUtil.fetch("http://hello") }
  }

  @Test
  @LockAndMockStatic([NetUtil::class])
  fun `test fetch with explicit maxSize enforces that limit, not the default`() {
    every { getCall(any()) } returns mockCall
    every { mockCall.execute() } returns mockResponse
    every { mockResponse.isSuccessful } returns true
    every { mockResponse.body } returns mockResponseBody
    every { mockResponseBody.contentType() } returns null
    every { mockResponseBody.byteStream() } returns ByteArrayInputStream(ByteArray(101))

    assertThrows(IOException::class.java) { NetUtil.fetch("http://hello", 100) }
  }

  @Test
  fun `test getCall()`() {
    val request = OkHttpUtil.buildGetRequest("https://www.stellar.org")
    assertNotNull(getCall(request))
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
  @LockStatic([NetUtil::class])
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
  @LockStatic([NetUtil::class])
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
  @LockStatic([NetUtil::class])
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
  @LockStatic([NetUtil::class])
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
  @LockStatic([NetUtil::class])
  fun `test good URLs for getDomainFromURL`(testUri: String, compareDomain: String) {
    val domain = getDomainFromURL(testUri)
    assertEquals(domain, compareDomain)
  }

  @ParameterizedTest
  @ValueSource(strings = ["bad url", "http :///test.stellar.org:9800/"])
  @LockStatic([NetUtil::class])
  fun `test bad URLs for getDomainFromURL`(testUrl: String) {
    org.junit.jupiter.api.assertThrows<MalformedURLException> { getDomainFromURL(testUrl) }
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "https://horizon-testnet.stellar.org",
        "HTTPS://Horizon.Stellar.Org",
        "https://203.0.113.7:8000/x",
        "https://localhost",
        "https://rpc.example.test/rpc?k=a|b",
        "https://stellar_rpc.internal/rpc"
      ]
  )
  @LockStatic([NetUtil::class])
  fun `test https URLs pass isHttpsOrLoopbackHttp for any host`(testValue: String) {
    assertTrue(isHttpsOrLoopbackHttp(testValue))
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "http://localhost",
        "http://LOCALHOST:8000/path",
        "HTTP://localhost:8000",
        "http://127.0.0.1:8000",
        "http://127.0.0.1",
        "http://[::1]:8000",
        "http://[::1]",
        // Spelled differently, but OkHttp connects to ::1 and 127.0.0.1: still loopback.
        "http://[0:0:0:0:0:0:0:1]:8000",
        "http://[::ffff:127.0.0.1]"
      ]
  )
  @LockStatic([NetUtil::class])
  fun `test http loopback URLs pass isHttpsOrLoopbackHttp`(testValue: String) {
    assertTrue(isHttpsOrLoopbackHttp(testValue))
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "http://horizon-testnet.stellar.org",
        "http://192.168.100.1",
        "http://10.0.0.5:8000",
        "http://127.0.0.2",
        "http://0.0.0.0",
        "http://host.docker.internal",
        "http://foo.localhost",
        // The word "https" in the host or the query does not make the connection https.
        "http://https.evil.example/",
        "http://evil.example/?next=https://x",
        "http://rpc.example.test/rpc?k=a|b",
        "http://127.1",
        "http://127.0.0.1.nip.io"
      ]
  )
  @LockStatic([NetUtil::class])
  fun `test http non-loopback URLs fail isHttpsOrLoopbackHttp`(testValue: String) {
    assertFalse(isHttpsOrLoopbackHttp(testValue))
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "http://localhost@evil.example",
        "http://localhost:80@evil.example",
        "http://localhost.evil.example",
        "http://127.0.0.1.evil.example",
        "http://evil.example/?h=localhost",
        "http://evil.example/localhost"
      ]
  )
  @LockStatic([NetUtil::class])
  fun `test loopback lookalikes fail isHttpsOrLoopbackHttp`(testValue: String) {
    assertFalse(isHttpsOrLoopbackHttp(testValue))
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
    strings =
      ["ftp://localhost", "file:///etc/passwd", "ws://localhost", "localhost:8000", "", "http://"]
  )
  @LockStatic([NetUtil::class])
  fun `test other schemes and unparsable values fail isHttpsOrLoopbackHttp`(testValue: String?) {
    assertFalse(isHttpsOrLoopbackHttp(testValue))
  }
}
