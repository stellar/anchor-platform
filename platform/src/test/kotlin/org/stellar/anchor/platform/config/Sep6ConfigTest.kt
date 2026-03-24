package org.stellar.anchor.platform.config

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.Sep6Config
import org.stellar.anchor.platform.utils.setupMock

class Sep6ConfigTest {
  @MockK(relaxed = true) lateinit var assetService: AssetService
  @MockK(relaxed = true) lateinit var secretConfig: SecretConfig
  lateinit var config: PropertySep6Config
  lateinit var errors: Errors

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    assetService = DefaultAssetService.fromJsonResource("test_assets.json")
    secretConfig.setupMock {}
    config =
      PropertySep6Config(assetService, secretConfig).apply {
        enabled = true
        features = Sep6Config.Features(false, false)
      }
    config.moreInfoUrl = MoreInfoUrlConfig("https://www.stellar.org", 600, listOf(""))
    errors = BindException(config, "config")
  }

  @Test
  fun `test disabled sep6 configuration skips remaining validation`() {
    config.enabled = false

    config.features = null
    config.validate(config, errors)
    Assertions.assertFalse(errors.hasErrors())

    config.features = Sep6Config.Features(true, true)
    config.validate(config, errors)
    Assertions.assertFalse(errors.hasErrors())
  }

  @Test
  fun `test valid sep6 configuration`() {
    config.validate(config, errors)
    Assertions.assertFalse(errors.hasErrors())
  }

  @Test
  fun `test validation passed without more_info_url config`() {
    config.moreInfoUrl = null
    config.validate(config, errors)
    Assertions.assertFalse(errors.hasErrors())
  }

  @Test
  fun `test validation rejecting undefined features config`() {
    config.features = null
    config.validate(config, errors)
    Assertions.assertEquals("sep6-features-invalid", errors.allErrors[0].code)
  }

  @Test
  fun `test validation rejecting account creation enabled`() {
    config.features = Sep6Config.Features(true, false)
    config.validate(config, errors)
    Assertions.assertEquals("sep6-features-account-creation-invalid", errors.allErrors[0].code)
  }

  @Test
  fun `test validation rejecting claimable balances enabled`() {
    config.features = Sep6Config.Features(false, true)
    config.validate(config, errors)
    Assertions.assertEquals("sep6-features-claimable-balances-invalid", errors.allErrors[0].code)
  }

  @Test
  fun `test validation rejecting invalid more_info_url config`() {
    config.moreInfoUrl = MoreInfoUrlConfig("httpss://www.stellar.org", 100, listOf(""))
    config.validate(config, errors)
    Assertions.assertEquals("sep6-more-info-url-base-url-not-valid", errors.allErrors[0].code)
  }

  @Test
  fun `test validation rejecting missing more_info_url jwt secret`() {
    secretConfig.setupMock { every { secretConfig.sep6MoreInfoUrlJwtSecret } returns null }
    config.validate(config, errors)
    Assertions.assertEquals("sep6-more-info-url-jwt-secret-not-defined", errors.allErrors[0].code)
  }

  @Test
  fun `validate interactive JWT`() {
    every { secretConfig.sep6MoreInfoUrlJwtSecret }.returns("tooshort")
    config.validate(config, errors)
    Assertions.assertTrue(errors.hasErrors())
    assertErrorCode(errors, "hmac-weak-secret")
  }
}
