package org.stellar.anchor.platform.utils

import io.mockk.every
import io.mockk.mockkStatic
import java.lang.reflect.Proxy
import org.slf4j.Logger
import org.stellar.anchor.util.Log

/**
 * Records every line the code under test sends through `org.stellar.anchor.util.Log`, at any level
 * and with any overload, so a test can prove that a value never reaches the log. [install] mocks
 * `Log` statically; the caller's `unmockkAll()` undoes it.
 */
class RecordingLogger {
  private val recorded = mutableListOf<String>()

  /** Everything logged since [install], one entry per call, arguments joined by a space. */
  val lines: List<String>
    get() = recorded.toList()

  private val logger: Logger =
    Proxy.newProxyInstance(Logger::class.java.classLoader, arrayOf(Logger::class.java)) {
      _,
      method,
      args ->
      if (method.returnType == Boolean::class.javaPrimitiveType) {
        true
      } else {
        // the `...F(format, args...)` overloads pass their arguments as one array
        val flat = args?.flatMap { if (it is Array<*>) it.toList() else listOf(it) }
        recorded.add(flat?.joinToString(" ") { it.toString() } ?: "")
        null
      }
    } as Logger

  fun install(): RecordingLogger {
    mockkStatic(Log::class)
    every { Log.getLogger() } returns logger
    return this
  }
}
