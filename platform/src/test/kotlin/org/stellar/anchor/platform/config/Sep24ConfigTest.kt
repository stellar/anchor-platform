package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import java.lang.Long.MIN_VALUE
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep24Config.DepositInfoGeneratorType
import org.stellar.anchor.config.Sep24Config.Features
import org.stellar.anchor.platform.config.PropertySep24Config.InteractiveUrlConfig
import org.stellar.anchor.platform.utils.setupMock

class Sep24ConfigTest {
  lateinit var config: PropertySep24Config
  lateinit var errors: Errors
  lateinit var secretConfig: SecretConfig
  lateinit var assetService: AssetService

  @BeforeEach
  fun setUp() {
    secretConfig = mockk()
    assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    secretConfig.setupMock()

    config = PropertySep24Config(secretConfig, assetService)
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
    config.depositInfoGeneratorType = DepositInfoGeneratorType.SELF
    (assetService.assets[0] as StellarAssetInfo).distributionAccount = null
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

  @Test
  fun `validate interactive JWT`() {
    every { secretConfig.sep24InteractiveUrlJwtSecret }.returns("tooshort")
    config.validate(config, errors)
    Assertions.assertTrue(errors.hasErrors())
    assertErrorCode(errors, "hmac-weak-secret")
  }

  @Test
  fun `validate more_info JWT`() {
    every { secretConfig.sep24MoreInfoUrlJwtSecret }.returns("tooshort")
    config.validate(config, errors)
    Assertions.assertTrue(errors.hasErrors())
    assertErrorCode(errors, "hmac-weak-secret")
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

  private fun features(accountCreation: Boolean, claimableBalances: Boolean) =
    Features().apply {
      this.accountCreation = accountCreation
      this.claimableBalances = claimableBalances
    }

  private fun featuresErrors(): List<Pair<String?, String?>> =
    errors.fieldErrors.filter { it.field == "features" }.map { it.code to it.defaultMessage }

  // SEP24IF-01
  @Test
  fun `test claimable balances enabled is rejected`() {
    config.features = features(accountCreation = false, claimableBalances = true)
    config.validate(config, errors)
    assertEquals(
      listOf(
        "sep24-features-claimable-balances-invalid" to
          "sep24.features.claimable_balances: claimable balances are not supported"
      ),
      featuresErrors(),
    )
  }

  // SEP24IF-02
  @Test
  fun `test account creation enabled is rejected`() {
    config.features = features(accountCreation = true, claimableBalances = false)
    config.validate(config, errors)
    assertEquals(
      listOf(
        "sep24-features-account-creation-invalid" to
          "sep24.features.account_creation: account creation is not supported"
      ),
      featuresErrors(),
    )
  }

  // SEP24IF-03
  @Test
  fun `test both features enabled reports both errors`() {
    config.features = features(accountCreation = true, claimableBalances = true)
    config.validate(config, errors)
    assertEquals(
      listOf(
        "sep24-features-account-creation-invalid" to
          "sep24.features.account_creation: account creation is not supported",
        "sep24-features-claimable-balances-invalid" to
          "sep24.features.claimable_balances: claimable balances are not supported",
      ),
      featuresErrors(),
    )
  }

  // SEP24IF-04
  @Test
  fun `test both features disabled and null features are accepted`() {
    config.features = features(accountCreation = false, claimableBalances = false)
    config.validate(config, errors)
    assertFalse(errors.hasErrors())

    errors = BindException(config, "config")
    config.features = null
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  // SEP24IF-05
  @Test
  fun `test disabled sep24 skips the features check`() {
    config.enabled = false
    config.features = features(accountCreation = true, claimableBalances = true)
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }
}
