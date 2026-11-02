package org.stellar.anchor.platform.controller

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.sentry.Sentry
import io.sentry.protocol.SentryId
import jakarta.persistence.PersistenceException
import java.sql.SQLException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ResponseStatus
import org.stellar.anchor.api.exception.BadRequestException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.api.exception.SepRateLimitExceededException
import org.stellar.anchor.platform.controller.observer.ObserverControllerExceptionHandler
import org.stellar.anchor.platform.controller.platform.PlatformControllerExceptionHandler
import org.stellar.anchor.platform.controller.sep.SepControllerExceptionHandler
import org.stellar.anchor.util.Log

/**
 * Covers the SEP-10 "GET /auth rejects requests with no 'account' parameter" gap: `account` is a
 * required `@RequestParam` on
 * [Sep10Controller][org.stellar.anchor.platform.controller.sep.Sep10Controller.createChallenge], so
 * a missing value never reaches that method -- Spring throws
 * [MissingServletRequestParameterException] first, which this handler is meant to turn into a clean
 * 400 instead of a generic error page.
 *
 * `handleMissingParams` and its `@ResponseStatus(BAD_REQUEST)` live entirely on
 * [AbstractControllerExceptionHandler], inherited unchanged by its three empty
 * `@RestControllerAdvice` subclasses ([SepControllerExceptionHandler],
 * `PlatformControllerExceptionHandler`, `ObserverControllerExceptionHandler`). This test targets
 * the abstract class directly (via one concrete subclass, since the behavior isn't SEP-specific) so
 * it protects all three, not just SEP-10's.
 *
 * This calls the advice method directly, which only proves the response body's message is correct
 * -- it does not go through real Spring MVC exception resolution, so it can't confirm the
 * `@ResponseStatus(BAD_REQUEST)` annotation actually produces an HTTP 400 for a live request. A
 * `@WebMvcTest` would prove that end-to-end, but this app boots multiple `@SpringBootApplication`
 * entry points (`SepServer`, `PlatformServer`, etc.) from shared `@ComponentScan` packages, and
 * `SepServer`'s scan pulls in `component.share` beans (e.g. `CallbackApiConfig`) that fail to bind
 * without a full application config -- there's no cheap way to slice-test just the web layer here.
 * Full end-to-end confirmation of the HTTP status instead comes from
 * `Sep10Tests.testAuthWithMissingAccountReturns400` in essential-tests' live-server integration
 * suite.
 */
class AbstractControllerExceptionHandlerTest {
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
      AbstractControllerExceptionHandler::class
        .java
        .getMethod("handleMissingParams", MissingServletRequestParameterException::class.java)

    val responseStatus = method.getAnnotation(ResponseStatus::class.java)

    assertEquals(HttpStatus.BAD_REQUEST, responseStatus.value)
  }

  @Test
  fun `rate limit exceeded returns the exception message`() {
    val handler = SepControllerExceptionHandler()
    val ex = SepRateLimitExceededException("too many requests")

    val response = handler.handleRateLimitExceeded(ex)

    assertEquals("too many requests", response.error)
  }

  @Test
  fun `rate limit handler method is annotated to produce HTTP 429`() {
    val method =
      AbstractControllerExceptionHandler::class
        .java
        .getMethod("handleRateLimitExceeded", SepRateLimitExceededException::class.java)

    val responseStatus = method.getAnnotation(ResponseStatus::class.java)

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, responseStatus.value)
  }

  // The driver text of the #4113076 PoC: a 300-character field value overflowing VARCHAR(255).
  private val pocMessage =
    "could not execute statement [ERROR: value too long for type character varying(255)] " +
      "[insert into sep31_transaction (amount_expected,fields,required_info_updates) " +
      "values (?,?,?)]"

  private val fixedBody = "An internal error occurred."

  @Test
  fun `data access exception answers a fixed 500 body`() {
    val response =
      SepControllerExceptionHandler()
        .handleInternalError(DataIntegrityViolationException(pocMessage))

    assertEquals(fixedBody, response.error)
  }

  @Test
  fun `transaction exception answers a fixed 500 body`() {
    val handler = SepControllerExceptionHandler()

    assertEquals(
      fixedBody,
      handler.handleInternalError(TransactionSystemException(pocMessage)).error
    )
    assertEquals(
      fixedBody,
      handler.handleInternalError(CannotCreateTransactionException(pocMessage)).error
    )
  }

  @Test
  fun `persistence exception answers a fixed 500 body`() {
    val response =
      SepControllerExceptionHandler().handleInternalError(PersistenceException(pocMessage))

    assertEquals(fixedBody, response.error)
  }

  @Test
  fun `sql exception answers a fixed 500 body`() {
    val response = SepControllerExceptionHandler().handleInternalError(SQLException(pocMessage))

    assertEquals(fixedBody, response.error)
  }

  @Test
  fun `wrapped persistence exception answers a fixed 500 body`() {
    val wrapped =
      RuntimeException(
        "outer $pocMessage",
        IllegalStateException("middle $pocMessage", SQLException(pocMessage))
      )

    val response = SepControllerExceptionHandler().handleInternalError(wrapped)

    assertEquals(fixedBody, response.error)
  }

  @Test
  fun `sanitized body leaks nothing`() {
    val response =
      SepControllerExceptionHandler()
        .handleInternalError(DataIntegrityViolationException(pocMessage))

    for (leaked in
      listOf(
        pocMessage,
        "insert into",
        "sep31_transaction",
        "required_info_updates",
        "varchar(255)",
        "value too long",
        "character varying",
      )) {
      assertFalse(
        response.error.contains(leaked, ignoreCase = true),
        "the body must not contain \"$leaked\": ${response.error}"
      )
    }
  }

  @Test
  fun `sanitized exception is logged and captured`() {
    val ex = DataIntegrityViolationException(pocMessage)
    mockkStatic(Log::class)
    mockkStatic(Sentry::class)
    try {
      every { Log.errorEx(any<Throwable>()) } just Runs
      every { Sentry.captureException(any()) } returns SentryId.EMPTY_ID

      SepControllerExceptionHandler().handleInternalError(ex)

      verify(exactly = 1) { Log.errorEx(ex) }
      verify(exactly = 1) { Sentry.captureException(ex) }
    } finally {
      unmockkStatic(Sentry::class)
      unmockkStatic(Log::class)
    }
  }

  @Test
  fun `other exception keeps its message`() {
    val response =
      SepControllerExceptionHandler().handleInternalError(IllegalStateException("not a db failure"))

    assertEquals("not a db failure", response.error)
  }

  @Test
  fun `internal error handler is annotated to produce HTTP 500`() {
    val method =
      AbstractControllerExceptionHandler::class
        .java
        .getMethod("handleInternalError", Exception::class.java)

    assertEquals(
      HttpStatus.INTERNAL_SERVER_ERROR,
      method.getAnnotation(ResponseStatus::class.java).value
    )
  }

  @Test
  fun `all three advices sanitize persistence errors`() {
    val advices: List<AbstractControllerExceptionHandler> =
      listOf(
        SepControllerExceptionHandler(),
        PlatformControllerExceptionHandler(),
        ObserverControllerExceptionHandler(),
      )

    for (advice in advices) {
      assertEquals(
        fixedBody,
        advice.handleInternalError(DataIntegrityViolationException(pocMessage)).error,
        advice.javaClass.simpleName
      )
    }
  }

  @Test
  fun `specific handlers are unchanged`() {
    // Typed as the abstract class: `handleNotFound` is package-private there.
    val handler: AbstractControllerExceptionHandler = SepControllerExceptionHandler()

    assertEquals("bad input", handler.handleBadRequest(BadRequestException("bad input")).error)
    assertEquals("no token", handler.handleAuthError(SepNotAuthorizedException("no token")).error)
    assertEquals("missing", handler.handleNotFound(SepNotFoundException("missing")).error)

    val statuses =
      mapOf(
        "handleBadRequest" to HttpStatus.BAD_REQUEST,
        "handleAuthError" to HttpStatus.FORBIDDEN,
        "handleNotFound" to HttpStatus.NOT_FOUND,
      )
    for ((name, status) in statuses) {
      val method =
        AbstractControllerExceptionHandler::class.java.declaredMethods.single { it.name == name }
      assertEquals(status, method.getAnnotation(ResponseStatus::class.java).value, name)
    }
  }
}
