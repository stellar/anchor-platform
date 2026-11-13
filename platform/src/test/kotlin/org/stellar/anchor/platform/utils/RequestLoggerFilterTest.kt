package org.stellar.anchor.platform.utils

import io.mockk.*
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.platform.config.AppLoggingConfig
import org.stellar.anchor.util.Log

@ExtendWith(LockAndMockTest::class)
class RequestLoggerFilterTest {
  private val noProxies = ClientIpResolver(TrustedProxies.parse(listOf()))
  private lateinit var mockConfig: AppLoggingConfig
  private lateinit var request: MockHttpServletRequest
  private lateinit var response: MockHttpServletResponse
  private lateinit var filterChain: FilterChain

  @BeforeEach
  fun setUp() {
    mockConfig = mockk<AppLoggingConfig>(relaxed = true)
    request = MockHttpServletRequest()
    response = MockHttpServletResponse()
    filterChain = mockk<FilterChain>(relaxed = true)
  }

  @Test
  fun `test when disabled the doFilterWithLogging() is not called`() {
    // arrange
    every { mockConfig.isRequestLoggerEnabled } returns false
    val a = spyk(RequestLoggerFilter(mockConfig, noProxies))

    // act
    a.doFilterInternal(request, response, filterChain)

    // assert
    verify(exactly = 1) { filterChain.doFilter(any(), any()) }
    verify(exactly = 0) { a.doFilterWithLogging(any(), any(), any()) }
  }

  @Test
  fun `test when enabled the doFilterWithLogging() is called`() {
    // arrange
    every { mockConfig.isRequestLoggerEnabled } returns true
    val a = spyk(RequestLoggerFilter(mockConfig, noProxies))

    // act
    a.doFilterInternal(request, response, filterChain)

    // assert
    verify(exactly = 1) { filterChain.doFilter(any(), any()) }
    verify(exactly = 1) { a.doFilterWithLogging(any(), any(), any()) }
  }

  private fun spoofedRequest() {
    request.remoteAddr = "198.51.100.99"
    request.addHeader("X-Forwarded-For", "203.0.113.9")
    request.addHeader("X-Real-IP", "203.0.113.8")
  }

  @Test
  fun `test when disabled the client ip attribute is the resolved address`() {
    // arrange
    every { mockConfig.isRequestLoggerEnabled } returns false
    spoofedRequest()

    // act
    RequestLoggerFilter(mockConfig, noProxies).doFilterInternal(request, response, filterChain)

    // assert
    assertEquals(
      "198.51.100.99",
      request.getAttribute(RequestLoggerFilter.ATTRIBUTE_CLIENT_IP_ADDRESS)
    )
  }

  @Test
  fun `test when enabled the client ip attribute is the resolved address`() {
    // arrange
    every { mockConfig.isRequestLoggerEnabled } returns true
    spoofedRequest()

    // act
    RequestLoggerFilter(mockConfig, noProxies).doFilterInternal(request, response, filterChain)

    // assert
    assertEquals(
      "198.51.100.99",
      request.getAttribute(RequestLoggerFilter.ATTRIBUTE_CLIENT_IP_ADDRESS)
    )
  }

  @Test
  @LockAndMockStatic([Log::class])
  fun `test the INFO line and the TRACE message carry the resolved address`() {
    // arrange
    every { mockConfig.isRequestLoggerEnabled } returns true
    spoofedRequest()
    val infoLines = mutableListOf<String>()
    val traceMessages = mutableListOf<String>()
    every { Log.infoF(any(), *anyVararg()) } answers
      {
        // the varargs arrive as one array argument
        infoLines.add(
          args.flatMap { if (it is Array<*>) it.toList() else listOf(it) }.joinToString(" ")
        )
      }
    every { Log.trace(any<String>()) } answers { traceMessages.add(firstArg()) }

    // act
    RequestLoggerFilter(mockConfig, noProxies).doFilterInternal(request, response, filterChain)

    // assert
    assertEquals(1, infoLines.size)
    assertTrue(infoLines[0].contains("198.51.100.99"), infoLines[0])
    assertFalse(infoLines[0].contains("203.0.113"), infoLines[0])
    assertEquals(1, traceMessages.size)
    assertTrue(traceMessages[0].contains("\"clientId\": \"198.51.100.99\""), traceMessages[0])
    assertFalse(traceMessages[0].contains("203.0.113"), traceMessages[0])
  }
}
