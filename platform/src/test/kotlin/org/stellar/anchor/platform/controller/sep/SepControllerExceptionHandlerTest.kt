package org.stellar.anchor.platform.controller.sep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.bind.MissingServletRequestParameterException

/**
 * Closes the SEP-10 "GET /auth rejects requests with no 'account' parameter" gap: `account` is a
 * required `@RequestParam` on [Sep10Controller.createChallenge], so a missing value never reaches
 * that method -- Spring throws [MissingServletRequestParameterException] first, which this shared,
 * cross-SEP handler turns into a clean 400 instead of a generic error page.
 */
class SepControllerExceptionHandlerTest {
  @Test
  fun `missing required request parameter returns a 400 with a clear message`() {
    val handler = SepControllerExceptionHandler()
    val ex = MissingServletRequestParameterException("account", "String")

    val response = handler.handleMissingParams(ex)

    assertEquals("The \"account\" parameter is missing.", response.error)
  }
}
