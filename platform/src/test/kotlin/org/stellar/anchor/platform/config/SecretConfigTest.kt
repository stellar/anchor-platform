package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `test bad sep45 simulating seed`() {
    every { secretManager.get(PropertySecretConfig.SECRET_SEP_45_SIMULATING_SIGNING_SEED) } returns
      "GBAD"
    config.validate(config, errors)
    assertEquals("sep45-simulating-seed-invalid", errors.allErrors[0].code)
  }

  @Test
  fun `test valid sep45 simulating seed`() {
    every { secretManager.get(PropertySecretConfig.SECRET_SEP_45_SIMULATING_SIGNING_SEED) } returns
      "SDRNY2YXLOGYNN5Z4QNUUH325KVOXOT2HV2XUVIK24AKNBTODZC26C3G"
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }
}
