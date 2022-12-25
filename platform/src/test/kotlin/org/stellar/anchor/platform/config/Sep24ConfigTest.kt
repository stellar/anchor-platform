package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors

class Sep24ConfigTest {
  lateinit var config: PropertySep24Config
  lateinit var errors: Errors

  @BeforeEach
  fun setUp() {
    config = PropertySep24Config()
    config.enabled = true
    errors = BindException(config, "config")
  }

  @Test
  fun `test valid sep24 configuration`() {
    config.interactiveJwtExpiration = 1200

    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Integer.MIN_VALUE, 0])
  fun `test bad interactive jwt expiration`(expiration: Int) {
    config.setInteractiveJwtExpiration(expiration)
    config.validate(config, errors)
    assertTrue(errors.hasErrors())
    assertErrorCode(errors, "sep24-interactive-url-invalid")
  }
}
