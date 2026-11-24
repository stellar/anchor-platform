package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.platform.configurator.SecretManager
import org.stellar.anchor.platform.utils.PublishedLiterals
import org.stellar.anchor.util.Log

/**
 * ANCHOR-1546: the secret checks run when the container creates the bean, so a bad configuration
 * stops the start. `SecretConfigTest` covers what `validate` reports; this class covers that the
 * start fails with it.
 */
class SecretConfigStartupTest {
  private lateinit var secretManager: SecretManager
  private val config = PropertySecretConfig()

  private val sevenProperties = PublishedLiterals.secretsByProperty.keys.toList()

  private val freshSecrets =
    sevenProperties.mapIndexed { i, property ->
      property to "fresh_secret_${i}_" + "0123456789abcdef".repeat(3)
    }

  @BeforeEach
  fun setup() {
    secretManager = mockk()
    mockkStatic(SecretManager::class)
    every { secretManager.get(any()) } returns null
    every { SecretManager.getInstance() } returns secretManager
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  private fun configure(vararg secrets: Pair<String, String>) {
    every { secretManager.get(any()) } returns null
    secrets.forEach { (property, value) -> every { secretManager.get(property) } returns value }
  }

  private fun startFailure(vararg secrets: Pair<String, String>): InvalidConfigException {
    configure(*secrets)
    return assertThrows(InvalidConfigException::class.java) { config.afterPropertiesSet() }
  }

  @Test
  fun `test the start fails on a published secret and names the property, never the value`() {
    PublishedLiterals.secretsByProperty.forEach { (property, value) ->
      val failure = startFailure(property to value)

      assertEquals(1, failure.messages.size, property)
      assertTrue(failure.message!!.contains("secret-published"), property)
      assertTrue(failure.message!!.contains(property), property)
      PublishedLiterals.secretsByProperty.values.forEach {
        assertFalse(failure.message!!.contains(it), "$property: the message repeats a secret")
      }
    }
  }

  @Test
  fun `test the start fails on two equal secrets and names both properties`() {
    val shared = "shared_secret_value_that_is_long_enough_"

    val failure =
      startFailure(
        PropertySecretConfig.SECRET_PLATFORM_API_AUTH_SECRET to shared,
        PropertySecretConfig.SECRET_SEP_10_JWT_SECRET to shared,
      )

    assertEquals(1, failure.messages.size)
    assertTrue(failure.message!!.contains("secrets.jwt.must_be_unique"))
    assertTrue(failure.message!!.contains(PropertySecretConfig.SECRET_PLATFORM_API_AUTH_SECRET))
    assertTrue(failure.message!!.contains(PropertySecretConfig.SECRET_SEP_10_JWT_SECRET))
    assertFalse(failure.message!!.contains(shared))
  }

  @Test
  fun `test the start fails on a weak sep45 secret without printing it`() {
    val weak = "w3akS3cr3t"

    val failure = startFailure(PropertySecretConfig.SECRET_SEP_45_JWT_SECRET to weak)

    assertEquals(1, failure.messages.size)
    assertTrue(failure.message!!.contains("hmac-weak-secret"))
    assertTrue(failure.message!!.contains(PropertySecretConfig.SECRET_SEP_45_JWT_SECRET))
    assertFalse(failure.message!!.contains(weak))
  }

  @Test
  fun `test every failing check is listed in one failure`() {
    val (first, second) =
      sevenProperties.filter { it != PropertySecretConfig.SECRET_SEP_45_JWT_SECRET }
    val weak = "w3akS3cr3t"

    val failure =
      startFailure(
        first to PublishedLiterals.secretsByProperty.getValue(first),
        second to PublishedLiterals.secretsByProperty.getValue(second),
        PropertySecretConfig.SECRET_SEP_45_JWT_SECRET to weak,
      )

    assertEquals(3, failure.messages.size)
    assertEquals(2, failure.messages.count { it.contains("secret-published") })
    assertEquals(1, failure.messages.count { it.contains("hmac-weak-secret") })
    assertTrue(failure.message!!.contains(first))
    assertTrue(failure.message!!.contains(second))
  }

  @Test
  fun `test seven distinct strong secrets start`() {
    configure(*freshSecrets.toTypedArray())

    assertDoesNotThrow { config.afterPropertiesSet() }
  }

  @Test
  fun `test no secret and only some secrets start`() {
    configure()
    assertDoesNotThrow { config.afterPropertiesSet() }

    configure(freshSecrets[0], freshSecrets[1])
    assertDoesNotThrow { config.afterPropertiesSet() }

    configure(PropertySecretConfig.SECRET_SEP_10_JWT_SECRET to "")
    assertDoesNotThrow { config.afterPropertiesSet() }
  }

  @Test
  fun `test a published signing seed warns once, names the property and does not stop the start`() {
    mockkStatic(Log::class)
    val warnings = mutableListOf<String>()
    every { Log.warn(any<String>()) } answers
      {
        warnings.add(firstArg())
        Unit
      }

    PublishedLiterals.signingSeeds.forEach { seed ->
      warnings.clear()
      configure(
        *freshSecrets.toTypedArray(),
        PropertySecretConfig.SECRET_SEP_10_SIGNING_SEED to seed,
      )

      assertDoesNotThrow { config.afterPropertiesSet() }

      assertEquals(1, warnings.size)
      assertTrue(warnings[0].contains(PropertySecretConfig.SECRET_SEP_10_SIGNING_SEED))
      assertFalse(warnings[0].contains(seed))
    }
  }

  @Test
  fun `test building the config by hand runs no validation`() {
    configure(
      PropertySecretConfig.SECRET_SEP_10_JWT_SECRET to
        PublishedLiterals.secretsByProperty.getValue(PropertySecretConfig.SECRET_SEP_10_JWT_SECRET)
    )

    assertDoesNotThrow { PropertySecretConfig() }
  }
}
