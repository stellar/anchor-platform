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
}
