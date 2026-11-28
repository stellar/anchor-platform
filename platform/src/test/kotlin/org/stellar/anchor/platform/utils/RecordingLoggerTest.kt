package org.stellar.anchor.platform.utils

import io.mockk.unmockkAll
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.util.Log

/**
 * `RecordingLogger` is what lets the secret tests claim that no log line repeats a secret. It must
 * see the value through every `Log` overload, or those claims prove nothing (ANCHOR-1546,
 * SECGUARD-04).
 */
class RecordingLoggerTest {
  private val value = "recorded-value-0123456789"

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  private fun recorded(log: () -> Unit): List<String> {
    val recorder = RecordingLogger().install()
    log()
    return recorder.lines
  }

  @Test
  fun `test the plain message overloads are recorded at every level`() {
    listOf<() -> Unit>(
        { Log.error(value) },
        { Log.warn(value) },
        { Log.info(value) },
        { Log.debug(value) },
        { Log.trace(value) },
      )
      .forEachIndexed { i, log ->
        assertTrue(recorded(log).any { it.contains(value) }, "plain overload #$i")
        unmockkAll()
      }
  }

  @Test
  fun `test the message and detail overloads are recorded`() {
    assertTrue(recorded { Log.error("failed: ", mapOf("k" to value)) }.any { it.contains(value) })
  }

  @Test
  fun `test the format overloads are recorded with their arguments`() {
    listOf<() -> Unit>(
        { Log.errorF("bad %s", value) },
        { Log.warnF("bad %s", value) },
        { Log.infoF("bad %s", value) },
        { Log.debugF("bad %s", value) },
        { Log.traceF("bad %s", value) },
      )
      .forEachIndexed { i, log ->
        assertTrue(recorded(log).any { it.contains(value) }, "format overload #$i")
        unmockkAll()
      }
  }
}
