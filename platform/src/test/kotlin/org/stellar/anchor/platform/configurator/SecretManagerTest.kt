package org.stellar.anchor.platform.configurator

import io.mockk.unmockkAll
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import org.stellar.anchor.platform.config.PropertySecretConfig.SECRET_SEP_10_JWT_SECRET
import org.stellar.anchor.platform.config.PropertySecretConfig.SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET
import org.stellar.anchor.platform.utils.RecordingLogger

/**
 * ANCHOR-1576 (HackerOne 4235840): a secret that the JVM decoded with lost characters (U+FFFD) must
 * stop the start, and a secret that was decoded correctly must load unchanged.
 */
class SecretManagerTest {
  private val replacement = "�"
  private val validSecret = "valid_secret_0123456789abcdef_0123456789abcdef"
  private val manager = SecretManager.getInstance()

  /** 25 Cyrillic letters, as the report's secret reads when the JVM decodes it correctly. */
  private val cyrillic = String(IntArray(25) { 0x0430 + it }, 0, 25)

  @BeforeEach
  fun setup() {
    manager.props.clear()
  }

  @AfterEach
  fun tearDown() {
    manager.props.clear()
    ConfigEnvironment.rebuild()
    unmockkAll()
  }

  private fun initialize(vararg env: Pair<String, String>): GenericApplicationContext {
    ConfigEnvironment.rebuild(env.toMap())
    val context = GenericApplicationContext()
    manager.initialize(context)
    return context
  }

  private fun rejection(vararg env: Pair<String, String>): IllegalStateException =
    assertFailsWith<IllegalStateException> { initialize(*env) }

  @Test
  fun `test every secret variable is rejected when its value contains U+FFFD`() {
    assertTrue(manager.secretVars.isNotEmpty())
    assertTrue(manager.secretVars.contains(SECRET_SEP_10_JWT_SECRET))

    manager.secretVars.forEach { variable ->
      val failure = rejection(variable to "abc${replacement}xyz")

      assertTrue(failure.message!!.contains(variable), "$variable: ${failure.message}")
    }
  }

  @Test
  fun `test the report's value, 50 replacement characters, is rejected without its length`() {
    val failure = rejection(SECRET_SEP_10_JWT_SECRET to replacement.repeat(50))

    assertTrue(failure.message!!.contains(SECRET_SEP_10_JWT_SECRET))
    assertFalse(failure.message!!.contains("50"), failure.message)
    assertFalse(failure.message!!.contains(replacement), failure.message)
  }

  @Test
  fun `test a mixed secret that lost its non-ASCII part is rejected`() {
    val failure = rejection(SECRET_SEP_10_JWT_SECRET to "abc$replacement${replacement}XYZ")

    assertTrue(failure.message!!.contains(SECRET_SEP_10_JWT_SECRET))
    assertFalse(failure.message!!.contains("abc"), failure.message)
    assertFalse(failure.message!!.contains("XYZ"), failure.message)
  }

  @Test
  fun `test a replacement character in the last position is rejected`() {
    rejection(SECRET_SEP_10_JWT_SECRET to "${validSecret}$replacement")
  }

  @Test
  fun `test the message names both remedies`() {
    val failure = rejection(SECRET_SEP_10_JWT_SECRET to replacement.repeat(50))

    assertTrue(failure.message!!.contains("LANG=C.UTF-8"), failure.message)
    assertTrue(failure.message!!.contains("-Dfile.encoding=UTF-8"), failure.message)
  }

  @Test
  fun `test neither the message nor the log line repeats any secret`() {
    val logger = RecordingLogger().install()

    val failure =
      rejection(
        SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET to validSecret,
        SECRET_SEP_10_JWT_SECRET to "abc$replacement${replacement}XYZ",
      )

    assertTrue(logger.lines.any { it.contains(SECRET_SEP_10_JWT_SECRET) }, "no error line logged")
    listOf(failure.message!!).plus(logger.lines).forEach { text ->
      assertFalse(text.contains(validSecret), text)
      assertFalse(text.contains("abc"), text)
      assertFalse(text.contains("XYZ"), text)
      assertFalse(text.contains(replacement), text)
    }
  }

  @Test
  fun `test neither the message nor the log line states the length of the rejected value`() {
    val logger = RecordingLogger().install()

    val failure = rejection(SECRET_SEP_10_JWT_SECRET to replacement.repeat(50))

    assertTrue(logger.lines.any { it.contains(SECRET_SEP_10_JWT_SECRET) }, "no error line logged")
    listOf(failure.message!!).plus(logger.lines).forEach { text ->
      assertFalse(text.contains("50"), text)
      assertFalse(text.contains("150"), text)
    }
  }

  @Test
  fun `test a single replacement character is rejected`() {
    val failure = rejection(SECRET_SEP_10_JWT_SECRET to replacement)

    assertTrue(failure.message!!.contains(SECRET_SEP_10_JWT_SECRET), failure.message)
  }

  @Test
  fun `test nothing is registered when a value is rejected, not even the valid ones`() {
    val context = GenericApplicationContext()
    ConfigEnvironment.rebuild(
      mapOf(
        SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET to validSecret,
        SECRET_SEP_10_JWT_SECRET to replacement.repeat(50),
      )
    )

    assertFailsWith<IllegalStateException> { manager.initialize(context) }

    assertFalse(context.environment.propertySources.contains("secret"))
    assertNull(manager.get(SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET))
    assertNull(manager.get(SECRET_SEP_10_JWT_SECRET))
  }

  @Test
  fun `test with two rejected values the first in the list is named`() {
    val failure =
      rejection(
        SECRET_SEP_10_JWT_SECRET to "abc$replacement",
        SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET to "def$replacement",
      )

    assertTrue(failure.message!!.contains(SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET), failure.message)
    assertFalse(failure.message!!.contains(SECRET_SEP_10_JWT_SECRET), failure.message)
  }

  @Test
  fun `test a correctly decoded non-ASCII secret is registered unchanged`() {
    val context =
      initialize(
        SECRET_SEP_10_JWT_SECRET to cyrillic,
        SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET to validSecret,
      )

    assertEquals(cyrillic, context.environment.getProperty(SECRET_SEP_10_JWT_SECRET))
    assertEquals(
      validSecret,
      context.environment.getProperty(SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET)
    )
    assertEquals(cyrillic, manager.get(SECRET_SEP_10_JWT_SECRET))
  }

  @Test
  fun `test a value with leading and trailing spaces is registered unchanged`() {
    val padded = " $validSecret "

    val context = initialize(SECRET_SEP_10_JWT_SECRET to padded)

    assertEquals(padded, context.environment.getProperty(SECRET_SEP_10_JWT_SECRET))
  }

  @Test
  fun `test a character outside the BMP is registered unchanged`() {
    val emoji = "pass😀word_0123456789abcdef_0123456789abcdef"

    val context = initialize(SECRET_SEP_10_JWT_SECRET to emoji)

    assertEquals(emoji, context.environment.getProperty(SECRET_SEP_10_JWT_SECRET))
  }

  @Test
  fun `test an unset or empty variable is skipped without error`() {
    val context = initialize(SECRET_SEP_10_JWT_SECRET to "")

    assertTrue(context.environment.propertySources.contains("secret"))
    assertNull(context.environment.getProperty(SECRET_SEP_10_JWT_SECRET))
    assertNull(context.environment.getProperty(SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET))
  }
}
