package org.stellar.anchor.platform.utils

import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter

/**
 * An SLF4J logger that records the rendered text of every call, whichever overload produced it
 * (`info(String)`, `info(String, Object)`, `info(String, Object...)`, and the same for the other
 * levels). Capturing a single overload passes vacuously against code that logs through another one,
 * so tests that assert the absence of a value use this logger and first assert that a line was
 * captured at all.
 */
class CapturingLogger : LegacyAbstractLogger() {
  private val captured = mutableListOf<Pair<Level, String>>()

  val allLines: List<String>
    get() = captured.map { it.second }

  val infoLines: List<String>
    get() = linesAt(Level.INFO)

  val debugLines: List<String>
    get() = linesAt(Level.DEBUG)

  private fun linesAt(level: Level): List<String> =
    captured.filter { it.first == level }.map { it.second }

  override fun getName(): String = "capturing"

  override fun isTraceEnabled(): Boolean = true

  override fun isDebugEnabled(): Boolean = true

  override fun isInfoEnabled(): Boolean = true

  override fun isWarnEnabled(): Boolean = true

  override fun isErrorEnabled(): Boolean = true

  override fun isTraceEnabled(marker: Marker?): Boolean = true

  override fun isDebugEnabled(marker: Marker?): Boolean = true

  override fun isInfoEnabled(marker: Marker?): Boolean = true

  override fun isWarnEnabled(marker: Marker?): Boolean = true

  override fun isErrorEnabled(marker: Marker?): Boolean = true

  override fun getFullyQualifiedCallerName(): String = CapturingLogger::class.java.name

  override fun handleNormalizedLoggingCall(
    level: Level,
    marker: Marker?,
    messagePattern: String?,
    arguments: Array<Any?>?,
    throwable: Throwable?,
  ) {
    synchronized(captured) {
      captured.add(level to MessageFormatter.arrayFormat(messagePattern, arguments).message)
    }
  }
}
