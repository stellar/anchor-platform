package org.stellar.anchor.platform.controller.platform

import io.mockk.mockk
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.stellar.anchor.api.rpc.RpcErrorCode
import org.stellar.anchor.api.rpc.RpcResponse
import org.stellar.anchor.platform.controller.AbstractControllerExceptionHandler
import org.stellar.anchor.platform.utils.JsonArrayLimitException
import org.stellar.anchor.platform.utils.JsonStructureLimitException
import org.stellar.anchor.platform.utils.RequestBodySizeLimitFilter
import org.stellar.anchor.util.GsonUtils

class JsonLimitExceptionHandlerTest {
  private val handler = PlatformControllerExceptionHandler()
  private val gson = GsonUtils.getInstance()
  private val logged = mutableListOf<String>()

  private val appender =
    object : AbstractAppender("json-limit-test", null, null, true, Property.EMPTY_ARRAY) {
      override fun append(event: LogEvent) {
        logged.add("${event.level} ${event.message.formattedMessage}")
      }
    }

  private val logger =
    (LogManager.getContext(false) as LoggerContext).getLogger(
      AbstractControllerExceptionHandler::class.java.name
    )

  @BeforeEach
  fun attachAppender() {
    appender.start()
    logger.addAppender(appender)
  }

  @AfterEach
  fun detachAppender() {
    logger.removeAppender(appender)
    appender.stop()
    RequestContextHolder.resetRequestAttributes()
  }

  private fun unreadable(cause: Throwable) =
    HttpMessageNotReadableException("Could not read JSON", cause, mockk<HttpInputMessage>())

  @Test
  fun `a node limit violation answers 400 with the too complex body`() {
    val response =
      handler.handleRandomException(
        unreadable(JsonStructureLimitException(JsonStructureLimitException.NODES, 10_000))
      )

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    assertEquals("""{"error":"The request body is too complex."}""", gson.toJson(response.body))
  }

  @Test
  fun `a depth limit violation answers 400 with the too complex body`() {
    val response =
      handler.handleRandomException(
        unreadable(JsonStructureLimitException(JsonStructureLimitException.DEPTH, 32))
      )

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    assertEquals("""{"error":"The request body is too complex."}""", gson.toJson(response.body))
  }

  @Test
  fun `a root array limit violation answers 200 with the rpc batch limit error`() {
    val response = handler.handleRandomException(unreadable(JsonArrayLimitException(null, 40)))

    assertEquals(HttpStatus.OK, response.statusCode)
    val body = response.body as List<*>
    assertEquals(1, body.size)
    val error = (body[0] as RpcResponse).error
    assertEquals("RPC batch size limit[40] exceeded", error.message)
    assertEquals(RpcErrorCode.INVALID_REQUEST.errorCode, error.code)
  }

  @Test
  fun `a records array limit violation answers 400 with the too many records body`() {
    val response =
      handler.handleRandomException(unreadable(JsonArrayLimitException("records", 1000)))

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    assertEquals(
      """{"error":"The number of records exceeds the limit of 1000."}""",
      gson.toJson(response.body),
    )
  }

  @Test
  fun `a limit exception nested below another cause is still found`() {
    val response =
      handler.handleRandomException(
        unreadable(RuntimeException("wrapper", JsonArrayLimitException("records", 5)))
      )

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    assertEquals(
      """{"error":"The number of records exceeds the limit of 5."}""",
      gson.toJson(response.body),
    )
  }

  @Test
  fun `a body size violation still answers 413`() {
    val response =
      handler.handleRandomException(
        unreadable(RequestBodySizeLimitFilter.RequestBodyTooLargeException(16))
      )

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.statusCode)
    assertEquals("""{"error":"The request body is too large."}""", gson.toJson(response.body))
  }

  @Test
  fun `a malformed body still answers 400 with the generic body`() {
    val response = handler.handleRandomException(unreadable(RuntimeException("Unexpected token")))

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    assertEquals(
      """{"error":"Your request body is wrong in some way."}""",
      gson.toJson(response.body),
    )
  }

  @Test
  fun `a refusal logs one warning with the route, the limit and its value and not the body`() {
    RequestContextHolder.setRequestAttributes(
      ServletRequestAttributes(MockHttpServletRequest("POST", "/"))
    )

    handler.handleRandomException(
      unreadable(JsonStructureLimitException(JsonStructureLimitException.NODES, 10_000))
    )

    val warnings = logged.filter { it.startsWith("WARN") }
    assertEquals(1, warnings.size)
    assertEquals(
      "WARN Rejecting POST /: the request body exceeds the limit max_json_nodes of 10000",
      warnings[0],
    )
  }

  @Test
  fun `a batch limit refusal logs one warning naming rpc batch_size_limit and its value`() {
    RequestContextHolder.setRequestAttributes(
      ServletRequestAttributes(MockHttpServletRequest("POST", "/"))
    )

    handler.handleRandomException(unreadable(JsonArrayLimitException(null, 40)))

    val warnings = logged.filter { it.startsWith("WARN") }
    assertEquals(
      listOf(
        "WARN Rejecting POST /: the request body exceeds the limit rpc.batch_size_limit of 40"
      ),
      warnings,
    )
  }

  @Test
  fun `a refusal outside a request logs the route as unknown`() {
    handler.handleRandomException(unreadable(JsonArrayLimitException("records", 1000)))

    val warnings = logged.filter { it.startsWith("WARN") }
    assertEquals(1, warnings.size)
    assertEquals(
      "WARN Rejecting unknown: the request body exceeds the limit records of 1000",
      warnings[0],
    )
  }

  @Test
  fun `a malformed body logs no limit warning`() {
    handler.handleRandomException(unreadable(RuntimeException("Unexpected token")))

    assertFalse(logged.any { it.contains("Rejecting") })
    assertTrue(logged.none { it.startsWith("WARN") })
  }
}
