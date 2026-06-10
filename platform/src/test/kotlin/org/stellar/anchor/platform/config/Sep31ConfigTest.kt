package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.api.asset.StellarAssetInfo
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.config.Sep31Config.DepositInfoGeneratorType

class Sep31ConfigTest {
  lateinit var config: PropertySep31Config
  lateinit var errors: Errors
  lateinit var assetService: AssetService

  @BeforeEach
  fun setUp() {
    assetService = DefaultAssetService.fromJsonResource("test_assets.json")

    config = PropertySep31Config(assetService)
    config.enabled = true
    errors = BindException(config, "config")
    config.depositInfoGeneratorType = DepositInfoGeneratorType.SELF
  }

  @Test
  fun `test valid sep31 configuration`() {
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test invalid deposit info generator type`() {
    config.depositInfoGeneratorType = DepositInfoGeneratorType.SELF
    (assetService.assets[0] as StellarAssetInfo).distributionAccount = null
    config.validate(config, errors)
    assertEquals("sep31-deposit-info-generator-type", errors.allErrors[0].code)
  }
}
