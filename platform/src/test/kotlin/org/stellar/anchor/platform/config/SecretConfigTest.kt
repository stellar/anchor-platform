package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.platform.configurator.SecretManager
import org.stellar.anchor.platform.utils.PublishedLiterals
import org.stellar.anchor.util.Log
import org.stellar.sdk.KeyPair

class SecretConfigTest {
  lateinit var config: PropertySecretConfig
  lateinit var errors: Errors
  private lateinit var secretManager: SecretManager

  @BeforeEach
  fun setup() {
    secretManager = mockk<SecretManager>()
    mockkStatic(SecretManager::class)
    every { secretManager.get(any()) } returns null
    every { SecretManager.getInstance() } returns secretManager

    config = PropertySecretConfig()
    errors = BindException(config, "config")
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `test weak sep45 jwt secret`() {
    every { secretManager.get(PropertySecretConfig.SECRET_SEP_45_JWT_SECRET) } returns "simple"
    config.validate(config, errors)
    assertEquals("hmac-weak-secret", errors.allErrors[0].code)
  }

  @Test
  fun `test valid sep45 jwt secret`() {
    every { secretManager.get(PropertySecretConfig.SECRET_SEP_45_JWT_SECRET) } returns
      "6a627a7fb025e2c5db643267523a1c801c1178bed30331a2606fe93f4dd9aa7b"
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test secret collision between platform api and sep10 is rejected`() {
    val sharedSecret = "shared_secret_value_that_is_long_enough_"
    every { secretManager.get(PropertySecretConfig.SECRET_PLATFORM_API_AUTH_SECRET) } returns
      sharedSecret
    every { secretManager.get(PropertySecretConfig.SECRET_SEP_10_JWT_SECRET) } returns sharedSecret

    config.validate(config, errors)

    assertEquals(1, errors.errorCount)
    assertEquals("secrets.jwt.must_be_unique", errors.allErrors[0].code)
  }

  @Test
  fun `test multiple secret collisions are all reported`() {
    val sharedSecret = "shared_secret_value_that_is_long_enough_"
    every { secretManager.get(PropertySecretConfig.SECRET_PLATFORM_API_AUTH_SECRET) } returns
      sharedSecret
    every { secretManager.get(PropertySecretConfig.SECRET_SEP_10_JWT_SECRET) } returns sharedSecret
    every { secretManager.get(PropertySecretConfig.SECRET_CALLBACK_API_AUTH_SECRET) } returns
      sharedSecret

    config.validate(config, errors)

    assertEquals(3, errors.errorCount)
    assertTrue(errors.allErrors.all { it.code == "secrets.jwt.must_be_unique" })
  }

  @Test
  fun `test distinct jwt secrets pass validation`() {
    every { secretManager.get(PropertySecretConfig.SECRET_PLATFORM_API_AUTH_SECRET) } returns
      "platform_secret_unique_value_32_chars___"
    every { secretManager.get(PropertySecretConfig.SECRET_SEP_10_JWT_SECRET) } returns
      "sep10_secret_unique_value_32_chars______"
    every { secretManager.get(PropertySecretConfig.SECRET_CALLBACK_API_AUTH_SECRET) } returns
      "callback_secret_unique_value_32_chars___"

    config.validate(config, errors)

    assertFalse(errors.hasErrors())
  }

  private fun validateWith(vararg secrets: Pair<String, String>): Errors {
    every { secretManager.get(any()) } returns null
    secrets.forEach { (property, value) -> every { secretManager.get(property) } returns value }
    val errs = BindException(config, "config")
    config.validate(config, errs)
    return errs
  }

  private val freshSecrets =
    PublishedLiterals.secretsByProperty.keys.mapIndexed { i, property ->
      property to "fresh_secret_${i}_" + "0123456789abcdef".repeat(3)
    }

  @Test
  fun `test published secret is rejected under its own property`() {
    PublishedLiterals.secretsByProperty.forEach { (property, value) ->
      val errs = validateWith(property to value)
      assertEquals(1, errs.errorCount, property)
      assertEquals("secret-published", errs.allErrors[0].code, property)
    }
  }

  @Test
  fun `test published secret is rejected under another property`() {
    val entries = PublishedLiterals.secretsByProperty.entries.toList()
    entries.forEachIndexed { i, (_, value) ->
      val otherProperty = entries[(i + 1) % entries.size].key
      val errs = validateWith(otherProperty to value)
      assertEquals(1, errs.errorCount, otherProperty)
      assertEquals("secret-published", errs.allErrors[0].code, otherProperty)
      assertTrue(errs.allErrors[0].defaultMessage!!.contains(otherProperty))
    }
  }

  @Test
  fun `test a published value shared by two properties is reported per property and as a collision`() {
    val (first, second) = PublishedLiterals.secretsByProperty.keys.toList()
    val value = PublishedLiterals.secretsByProperty.getValue(first)
    val errs = validateWith(first to value, second to value)
    assertEquals(3, errs.errorCount)
    assertEquals(2, errs.allErrors.count { it.code == "secret-published" })
    assertEquals(1, errs.allErrors.count { it.code == "secrets.jwt.must_be_unique" })
  }

  @Test
  fun `test fresh secrets and near misses are not reported as published`() {
    assertFalse(validateWith(*freshSecrets.toTypedArray()).hasErrors())
    PublishedLiterals.secretsByProperty.forEach { (property, value) ->
      assertFalse(validateWith(property to (value + "x")).hasErrors(), "$property plus a character")
      assertFalse(validateWith(property to " $value").hasErrors(), "$property leading space")
      assertFalse(validateWith(property to "$value\n").hasErrors(), "$property trailing newline")
      assertFalse(validateWith(property to "").hasErrors(), "$property empty")
    }
  }

  @Test
  fun `test published secret error names the property and not the value`() {
    PublishedLiterals.secretsByProperty.forEach { (property, value) ->
      val errs = validateWith(property to value)
      val message = errs.allErrors[0].defaultMessage!!
      assertTrue(message.contains(property), message)
      PublishedLiterals.secretsByProperty.values.forEach { assertFalse(message.contains(it)) }
    }
  }

  @Test
  fun `test published signing seed logs a warning and adds no error`() {
    mockkStatic(Log::class)
    val warning = mutableListOf<String>()
    every { Log.warn(any<String>()) } answers
      {
        warning.add(firstArg())
        Unit
      }
    PublishedLiterals.signingSeeds.forEach { seed ->
      warning.clear()
      val errs = validateWith(PropertySecretConfig.SECRET_SEP_10_SIGNING_SEED to seed)
      assertFalse(errs.hasErrors(), "a published seed is a warning, not an error")
      assertEquals(1, warning.size)
      assertTrue(warning[0].contains(PropertySecretConfig.SECRET_SEP_10_SIGNING_SEED))
      assertFalse(warning[0].contains(seed))
    }
    warning.clear()
    listOf(" ", "\n").forEach { extra ->
      warning.clear()
      validateWith(
        PropertySecretConfig.SECRET_SEP_10_SIGNING_SEED to
          (PublishedLiterals.signingSeeds[0] + extra)
      )
      assertTrue(warning.isEmpty(), "a seed with a trailing character is a different seed")
    }
    warning.clear()
    val fresh = String(KeyPair.random().secretSeed)
    assertFalse(validateWith(PropertySecretConfig.SECRET_SEP_10_SIGNING_SEED to fresh).hasErrors())
    assertTrue(warning.isEmpty())
  }
}
