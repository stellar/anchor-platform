package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.validation.BindException
import org.springframework.validation.Errors

class RateLimitConfigTest {
  lateinit var config: PropertyRateLimitConfig
  lateinit var errors: Errors

  @BeforeEach
  fun setup() {
    config = PropertyRateLimitConfig()
    errors = BindException(config, "config")
  }

  @Test
  fun `test default config is disabled and valid`() {
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
    assertFalse(config.enabled)
  }

  @Test
  fun `test enabled config with valid values passes`() {
    config.enabled = true
    config.maxTransactionsPerWindow = 5
    config.windowSeconds = 30
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test enabled config rejects non-positive max transactions`() {
    config.enabled = true
    config.maxTransactionsPerWindow = 0
    config.validate(config, errors)
    assertErrorCode(errors, "rate-limit-max-transactions-per-window-invalid")
  }

  @Test
  fun `test enabled config rejects non-positive window seconds`() {
    config.enabled = true
    config.windowSeconds = -1
    config.validate(config, errors)
    assertErrorCode(errors, "rate-limit-window-seconds-invalid")
  }

  @Test
  fun `test invalid values are ignored when disabled`() {
    config.enabled = false
    config.maxTransactionsPerWindow = -5
    config.windowSeconds = 0
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }
}
