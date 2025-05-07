package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors

class Sep38ConfigTest {
  lateinit var config: PropertySep38Config
  lateinit var errors: Errors

  @BeforeEach
  fun setup() {
    config = PropertySep38Config()
    config.enabled = true
    config.sep10Enforced = true
    config.authEnforced = true
    errors = BindException(config, "config")
  }

  @Test
  fun `test default sep38 config`() {
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @CsvSource("true,false", "false,true")
  @ParameterizedTest
  fun `test mismatched sep10Enforced and authEnforced`(
    sep10Enforced: Boolean,
    authEnforced: Boolean
  ) {
    config.sep10Enforced = sep10Enforced
    config.authEnforced = authEnforced
    config.validate(config, errors)
    assertErrorCode(errors, "sep38-auth-enforced-mismatch")
  }
}
