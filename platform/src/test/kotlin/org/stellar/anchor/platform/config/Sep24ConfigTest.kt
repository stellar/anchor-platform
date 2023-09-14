package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import java.lang.Long.MIN_VALUE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.config.CustodyConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep24Config.DepositInfoGeneratorType
import org.stellar.anchor.config.Sep24Config.Features
import org.stellar.anchor.platform.config.PropertySep24Config.InteractiveUrlConfig
import org.stellar.anchor.platform.config.PropertySep24Config.MoreInfoUrlConfig

class Sep24ConfigTest {
  lateinit var config: PropertySep24Config
  lateinit var errors: Errors
  lateinit var secretConfig: SecretConfig
  lateinit var custodyConfig: CustodyConfig

  @BeforeEach
  fun setUp() {
    secretConfig = mockk()
    custodyConfig = mockk()
    every { secretConfig.sep24MoreInfoUrlJwtSecret } returns "more_info url jwt secret"
    every { secretConfig.sep24InteractiveUrlJwtSecret } returns "interactive url jwt secret"
    every { custodyConfig.isCustodyIntegrationEnabled } returns false

    config = PropertySep24Config(secretConfig, custodyConfig)
    config.enabled = true
    errors = BindException(config, "config")
    config.interactiveUrl = InteractiveUrlConfig("https://www.stellar.org", 600, listOf(""))
    config.moreInfoUrl = MoreInfoUrlConfig("https://www.stellar.org", 600, listOf(""))
    config.depositInfoGeneratorType = DepositInfoGeneratorType.SELF
  }

  @Test
  fun `test valid sep24 configuration`() {
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test invalid deposit info generator type`() {
    config.depositInfoGeneratorType = DepositInfoGeneratorType.CUSTODY
    config.validate(config, errors)
    assertEquals("sep24-deposit-info-generator-type", errors.allErrors[0].code)
  }

  @Test
  fun `test valid sep24 configuration with custody integration`() {
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
    config.features = Features()
    config.features.accountCreation = false
    config.features.claimableBalances = false
    config.depositInfoGeneratorType = DepositInfoGeneratorType.CUSTODY
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test invalid deposit info generator type with custody integration`() {
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
    config.features = Features()
    config.features.accountCreation = false
    config.features.claimableBalances = false
    config.validate(config, errors)
    assertEquals("sep24-deposit-info-generator-type", errors.allErrors[0].code)
  }

  @Test
  fun `test validation rejecting missing more_info url jwt secret`() {
    every { secretConfig.sep24MoreInfoUrlJwtSecret } returns null
    config.validate(config, errors)
    assertEquals("sep24-more-info-url-jwt-secret-not-defined", errors.allErrors[0].code)
  }

  @Test
  fun `test validation rejecting missing interactive url jwt secret`() {
    every { secretConfig.sep24InteractiveUrlJwtSecret } returns null
    config.validate(config, errors)
    assertEquals("sep24-interactive-url-jwt-secret-not-defined", errors.allErrors[0].code)
  }

  @ParameterizedTest
  @ValueSource(strings = ["httpss://www.stellar.org"])
  fun `test interactive url with bad url configuration`(url: String) {
    config.interactiveUrl = InteractiveUrlConfig(url, 600, listOf(""))

    config.validate(config, errors)
    assertEquals("sep24-interactive-url-base-url-not-valid", errors.allErrors[0].code)
  }

  @ParameterizedTest
  @ValueSource(longs = [-1, MIN_VALUE, 0])
  fun `test interactive url with invalid jwt_expiration`(expiration: Long) {
    config.interactiveUrl = InteractiveUrlConfig("https://www.stellar.org", expiration, listOf(""))

    config.validate(config, errors)
    assertEquals("sep24-interactive-url-jwt-expiration-not-valid", errors.allErrors[0].code)
  }

  @ParameterizedTest
  @ValueSource(strings = ["httpss://www.stellar.org"])
  fun `test more_info_url with invalid url`(url: String) {
    config.moreInfoUrl = MoreInfoUrlConfig(url, 100, listOf(""))

    config.validate(config, errors)
    assertEquals("sep24-more-info-url-base-url-not-valid", errors.allErrors[0].code)
  }

  @ParameterizedTest
  @ValueSource(longs = [-1, MIN_VALUE, 0])
  fun `test more_info_url with invalid jwt_expiration`(expiration: Long) {
    config.moreInfoUrl = MoreInfoUrlConfig("https://www.stellar.org", expiration, listOf(""))

    config.validate(config, errors)
    assertEquals("sep24-more-info-url-jwt-expiration-not-valid", errors.allErrors[0].code)
  }

  @Test
  fun `test validate accountCreation = true and claimableBalances = true with custody integration`() {
    config.features = Features()
    config.features.accountCreation = true
    config.features.claimableBalances = true
    config.depositInfoGeneratorType = DepositInfoGeneratorType.CUSTODY
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
    config.validate(config, errors)
    assertEquals("sep24-features-account_creation-not-supported", errors.allErrors[0].code)
    assertEquals("sep24-features-claimable_balances-not-supported", errors.allErrors[1].code)
  }

  @Test
  fun `test validate accountCreation = false and claimableBalances = false with custody integration`() {
    config.features = Features()
    config.features.accountCreation = false
    config.features.claimableBalances = false
    config.depositInfoGeneratorType = DepositInfoGeneratorType.CUSTODY
    every { custodyConfig.isCustodyIntegrationEnabled } returns true
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test validate accountCreation = true and claimableBalances = true with disabled custody integration`() {
    config.features = Features()
    config.features.accountCreation = true
    config.features.claimableBalances = true
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }
}
