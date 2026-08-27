package org.stellar.anchor.platform.controller.sep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Covers the SEP-10 "GET /auth rejects requests with no 'account' parameter" gap: `account` is a
 * required `@RequestParam` on [Sep10Controller.createChallenge], so a missing value never reaches
 * that method -- Spring throws [MissingServletRequestParameterException] first, which this shared,
 * cross-SEP handler is meant to turn into a clean 400 instead of a generic error page.
 *
 * This calls the advice method directly, which only proves the response body's message is correct
 * -- it does not go through real Spring MVC exception resolution, so it can't confirm the
 * `@ResponseStatus(BAD_REQUEST)` annotation actually produces an HTTP 400 for a live `GET /auth`
 * request. A `@WebMvcTest` would prove that end-to-end, but this app boots multiple
 * `@SpringBootApplication` entry points (`SepServer`, `PlatformServer`, etc.) from shared
 * `@ComponentScan` packages, and `SepServer`'s scan pulls in `component.share` beans (e.g.
 * `CallbackApiConfig`) that fail to bind without a full application config -- there's no cheap way
 * to slice-test just the web layer here. Full end-to-end confirmation of the HTTP status would need
 * to go through essential-tests' live-server integration suite instead.
 */
class SepControllerExceptionHandlerTest {
  @Test
  fun `missing required request parameter returns a 400 with a clear message`() {
    val handler = SepControllerExceptionHandler()
    val ex = MissingServletRequestParameterException("account", "String")

    val response = handler.handleMissingParams(ex)

    assertEquals("The \"account\" parameter is missing.", response.error)
  }

  @Test
  fun `handler method is annotated to produce HTTP 400`() {
    val method =
      SepControllerExceptionHandler::class
        .java
        .getMethod("handleMissingParams", MissingServletRequestParameterException::class.java)

    val responseStatus = method.getAnnotation(ResponseStatus::class.java)

    assertEquals(HttpStatus.BAD_REQUEST, responseStatus.value)
  }
}
