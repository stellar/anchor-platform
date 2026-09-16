package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.validation.BindException
import org.springframework.validation.Errors

class PropertySep38ConfigTest {
  private lateinit var config: PropertySep38Config
  private lateinit var errors: Errors

  @BeforeEach
  fun setup() {
    config = PropertySep38Config()
    config.enabled = true
    config.sep10Enforced = false
    config.authEnforced = false
    errors = BindException(config, "config")
  }

  @Test
  fun `maxQuoteExpirationSeconds defaults to a positive value`() {
    config.validate(config, errors)

    assertFalse(errors.hasErrors())
    assertTrue(config.maxQuoteExpirationSeconds > 0)
  }

  @Test
  fun `maxQuoteExpirationSeconds null fails validation`() {
    config.maxQuoteExpirationSeconds = null

    config.validate(config, errors)

    assertTrue(errors.hasErrors())
  }

  @Test
  fun `maxQuoteExpirationSeconds zero fails validation`() {
    config.maxQuoteExpirationSeconds = 0

    config.validate(config, errors)

    assertTrue(errors.hasErrors())
  }

  @Test
  fun `maxQuoteExpirationSeconds negative fails validation`() {
    config.maxQuoteExpirationSeconds = -1

    config.validate(config, errors)

    assertTrue(errors.hasErrors())
  }

  @Test
  fun `maxQuoteExpirationSeconds positive passes validation`() {
    config.maxQuoteExpirationSeconds = 3600

    config.validate(config, errors)

    assertFalse(errors.hasErrors())
  }
}
