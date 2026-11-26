package org.stellar.anchor.platform.utils

import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.filter.FormContentFilter

/**
 * HackerOne #4233342 (ANCHOR-1567): Spring's [FormContentFilter] parses the whole body of a
 * form-encoded PUT/PATCH/DELETE (about 30 times its size in memory) before any other filter runs.
 * The real [FormContentFilter] is in every chain below, so the multiplication happens in the test.
 */
class FormBodySizeLimitFilterTest {
  private val limit = 1024L
  private val form = "application/x-www-form-urlencoded"

  /** Counts the requests that got past every filter, and records what the route would read. */
  private class StubRoute : HttpServlet() {
    val reached = AtomicInteger()
    var memo: String? = null
    var memoType: String? = null
    var parameterCount = -1

    override fun service(req: HttpServletRequest, res: HttpServletResponse) {
      reached.incrementAndGet()
      memo = req.getParameter("memo")
      memoType = req.getParameter("memo_type")
      parameterCount = req.parameterMap.size
      res.status = HttpServletResponse.SC_OK
    }
  }

  private lateinit var route: StubRoute

  @BeforeEach
  fun setup() {
    route = StubRoute()
  }

  private fun request(
    method: String,
    body: ByteArray,
    contentType: String? = form,
    uri: String = "/sep12/customer",
    chunked: Boolean = false,
  ): MockHttpServletRequest {
    val request =
      if (chunked)
        object : MockHttpServletRequest(method, uri) {
          override fun getContentLength() = -1

          override fun getContentLengthLong() = -1L
        }
      else MockHttpServletRequest(method, uri)
    request.setContent(body)
    if (contentType != null) request.contentType = contentType
    return request
  }

  private fun run(
    request: MockHttpServletRequest,
    vararg before: Filter,
  ): MockHttpServletResponse {
    val response = MockHttpServletResponse()
    // The new filter first, then the real Spring form filter, as the SEP server orders them.
    MockFilterChain(route, FormBodySizeLimitFilter(limit), *before, FormContentFilter())
      .doFilter(request, response)
    return response
  }

  private fun assertRefused(response: MockHttpServletResponse) {
    assertEquals(413, response.status)
    assertTrue(response.contentType!!.startsWith("application/json"))
    assertEquals("{\"error\":\"The request body is too large.\"}", response.contentAsString)
    assertEquals(0, route.reached.get(), "the form must not be parsed and the route not reached")
  }

  private fun oversized() = ByteArray((limit + 1).toInt()) { 'a'.code.toByte() }

  // FORM-01
  @Test
  fun `PUT with a form body above the limit is refused before the form is parsed`() {
    assertRefused(run(request("PUT", ("a=" + "b".repeat(limit.toInt())).toByteArray())))
  }

  // FORM-02
  @Test
  fun `PATCH with a form body above the limit is refused`() {
    assertRefused(run(request("PATCH", oversized())))
  }

  // FORM-03
  @Test
  fun `DELETE of a SEP-12 customer with a form body above the limit is refused`() {
    assertRefused(run(request("DELETE", oversized(), uri = "/sep12/customer/GABC")))
  }

  // FORM-04
  @Test
  fun `a chunked PUT without Content-Length is cut off at the limit and refused`() {
    val body = ("a=" + "b".repeat(limit.toInt() * 4)).toByteArray()
    assertRefused(run(request("PUT", body, chunked = true)))
  }

  // FORM-05
  @Test
  fun `the reported payload of a million short names is refused and never reaches the route`() {
    val body = (0 until 1_000_000).joinToString("&").toByteArray()
    assertTrue(body.size > 6_000_000)
    assertRefused(run(request("PUT", body)))
  }

  // FORM-06
  @Test
  fun `an unauthenticated oversized form is answered 413, not 403`() {
    val tokenCheck = Filter { req, res, chain ->
      if ((req as HttpServletRequest).getHeader("Authorization") == null)
        (res as HttpServletResponse).sendError(403)
      else chain.doFilter(req, res)
    }
    val request = request("PUT", oversized())
    assertNull(request.getHeader("Authorization"))
    assertRefused(run(request, tokenCheck))
  }

  // FORM-07
  @Test
  fun `a normal DELETE form still delivers memo and memo_type to the route`() {
    val response =
      run(request("DELETE", "memo=123&memo_type=id".toByteArray(), uri = "/sep12/customer/GABC"))
    assertEquals(200, response.status)
    assertEquals(1, route.reached.get())
    assertEquals("123", route.memo)
    assertEquals("id", route.memoType)
  }

  // FORM-08
  @Test
  fun `a form body of exactly the limit passes, one byte more is refused`() {
    val exact = "a=" + "b".repeat(limit.toInt() - 2)
    assertEquals(limit.toInt(), exact.length)
    assertEquals(200, run(request("PUT", exact.toByteArray())).status)
    assertEquals(1, route.reached.get())
    assertEquals(1, route.parameterCount)

    route = StubRoute()
    assertRefused(run(request("PUT", (exact + "b").toByteArray())))
  }

  // FORM-09
  @Test
  fun `bodies the form filter does not parse are not refused`() {
    listOf(
        request("POST", oversized(), contentType = form),
        request("GET", oversized(), contentType = form),
        request("PUT", oversized(), contentType = "application/json"),
        request("PUT", oversized(), contentType = "multipart/form-data; boundary=x"),
        request("PUT", oversized(), contentType = null),
      )
      .forEach {
        route = StubRoute()
        assertEquals(200, run(it).status, "${it.method} ${it.contentType}")
        assertEquals(1, route.reached.get(), "${it.method} ${it.contentType}")
      }
  }

  // FORM-10
  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "application/x-www-form-urlencoded; charset=UTF-8",
        "APPLICATION/X-WWW-FORM-URLENCODED",
        "Application/X-Www-Form-Urlencoded;charset=utf-8",
      ]
  )
  fun `the content type is matched case-insensitively and ignoring parameters`(type: String) {
    assertRefused(run(request("PUT", oversized(), contentType = type)))
  }

  // Edge case: a committed response is never written twice.
  @Test
  fun `a limit hit after the response is committed is rethrown, not written over`() {
    val committing = Filter { _, res, _ ->
      res.writer.write("started")
      res.flushBuffer()
      throw RequestBodySizeLimitFilter.RequestBodyTooLargeException(limit)
    }
    val response = MockHttpServletResponse()
    assertThrows(RequestBodySizeLimitFilter.RequestBodyTooLargeException::class.java) {
      MockFilterChain(route, FormBodySizeLimitFilter(limit), committing)
        .doFilter(request("PUT", "a=b".toByteArray()), response)
    }
    assertEquals("started", response.contentAsString)
  }
}
