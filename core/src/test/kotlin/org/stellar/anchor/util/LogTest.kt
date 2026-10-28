@file:Suppress("unused")

package org.stellar.anchor.util

import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.Logger
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.config.PII
import org.stellar.anchor.util.Log.shorter

@ExtendWith(LockAndMockTest::class)
internal class LogTest {
  @MockK(relaxed = true) private lateinit var logger: Logger

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxed = true)
  }

  class TestBeanPII {
    val fieldNoPII: String = "no secret"

    @PII val fieldPII: String = "secret"
  }

  private val wantTestPIIJson = """{"fieldNoPII":"no secret"}"""

  @Test
  @LockAndMockStatic([Log::class])
  fun `test log messages`() {
    every { Log.getLogger() } returns logger

    Log.error("Hello")
    verify { logger.error("Hello") }

    Log.warn("Hello")
    verify { logger.warn("Hello") }

    Log.info("Hello")
    verify { logger.info("Hello") }

    Log.debug("Hello")
    verify { logger.debug("Hello") }

    Log.trace("Hello")
    verify { logger.trace("Hello") }
  }

  @Test
  @LockAndMockStatic([Log::class])
  fun `test log messages with JSON format`() {
    every { Log.getLogger() } returns logger
    val detail = TestBeanPII()

    Log.error("Hello", detail)
    verify { logger.error("Hello$wantTestPIIJson") }

    Log.warn("Hello", detail)
    verify { logger.warn("Hello$wantTestPIIJson") }

    Log.info("Hello", detail)
    verify { logger.info("Hello$wantTestPIIJson") }

    Log.debug("Hello", detail)
    verify { logger.debug("Hello$wantTestPIIJson") }

    Log.trace("Hello", detail)
    verify { logger.trace("Hello$wantTestPIIJson") }
  }

  @Test
  @LockAndMockStatic([Log::class])
  fun `test errorEx`() {
    every { logger.error(any()) } answers {}

    every { Log.getLogger() } returns logger
    Log.errorEx(Exception("mock exception"))
    verify(exactly = 1) { logger.error(ofType(String::class)) }

    val slot = slot<String>()
    every { logger.error(capture(slot)) } answers {}

    Log.errorEx("Hello", Exception("mock exception"))
    assertTrue(slot.captured.contains("Hello"))
    assertTrue(slot.captured.contains("mock exception"))
  }

  @Test
  @LockAndMockStatic([Log::class])
  fun `test shorter string conversion`() {
    // ANCHOR-1395 (HackerOne #4107899): a value too short to truncate without exposing it in
    // full must be masked, never returned unchanged -- shorter() is relied on as a log redaction.
    assertNull(shorter(null))
    assertEquals(shorter(""), "")
    assertEquals(shorter("123"), "***")
    assertEquals(shorter("12345678"), "***")
    assertEquals(shorter("ABCD123ABCD"), "***")
    assertEquals(shorter("ABCD123_ABCD"), "ABCD...ABCD")
  }

  @Test
  @LockAndMockStatic([Log::class])
  fun `test getLogger`() {
    val logger = Log.getLogger()
    assertNotNull(logger)
  }
}
