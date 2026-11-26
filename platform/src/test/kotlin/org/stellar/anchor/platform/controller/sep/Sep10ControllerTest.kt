package org.stellar.anchor.platform.controller.sep

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.sep.sep10.ValidationRequest
import org.stellar.anchor.sep10.Sep10Service
import org.stellar.anchor.util.Log

// ANCHOR-1540 (HackerOne #4207658): the signed challenge is a credential; it must not reach the
// DEBUG log through either variant of POST /auth.
@ExtendWith(LockAndMockTest::class)
class Sep10ControllerTest {
  private val challenge = "AAAAAgAAAAD-signed-challenge-4207658"
  private val sep10Service = mockk<Sep10Service>(relaxed = true)
  private val controller = Sep10Controller(sep10Service)
  private val logger = CapturingLogger()

  // DBGLOG-10, DBGLOG-13, DBGLOG-15
  @Test
  @LockAndMockStatic([Log::class])
  fun `test form validateChallenge DEBUG line omits the signed challenge`() {
    every { Log.getLogger() } returns logger

    controller.validateChallenge(challenge)

    assertTrue(logger.debugLines.isNotEmpty())
    assertFalse(logger.allLines.joinToString("\n").contains(challenge))
    assertTrue(logger.debugLines.any { it.contains("POST /auth") })
    verify { sep10Service.validateChallenge(match { it.transaction == challenge }) }
  }

  // DBGLOG-11, DBGLOG-13, DBGLOG-15
  @Test
  @LockAndMockStatic([Log::class])
  fun `test JSON validateChallenge DEBUG line omits the signed challenge`() {
    every { Log.getLogger() } returns logger

    controller.validateChallenge(ValidationRequest.of(challenge))

    assertTrue(logger.debugLines.isNotEmpty())
    assertFalse(logger.allLines.joinToString("\n").contains(challenge))
    assertTrue(logger.debugLines.any { it.contains("POST /auth") })
    verify { sep10Service.validateChallenge(match { it.transaction == challenge }) }
  }

  // DBGLOG-14: the exclusion lives on the DTO, so any JSON-based Log method honors it
  @Test
  @LockAndMockStatic([Log::class])
  fun `test sep10 ValidationRequest transaction is excluded by every JSON Log method`() {
    every { Log.getLogger() } returns logger
    val request = ValidationRequest.of(challenge)

    Log.info("request:", request)
    Log.debug("request:", request)
    Log.trace("request:", request)

    // other threads may share the mocked logger, so look only at this test's own lines
    val ownLines = logger.allLines.filter { it.startsWith("request:") }
    assertEquals(3, ownLines.size)
    assertFalse(logger.allLines.joinToString("\n").contains(challenge))
  }
}
