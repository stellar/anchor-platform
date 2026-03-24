package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.platform.config.FireblocksConfig.Reconciliation
import org.stellar.anchor.platform.config.FireblocksConfig.RetryConfig
import org.stellar.anchor.util.FileUtil

class FireblocksConfigTest {

  private lateinit var config: FireblocksConfig
  private lateinit var secretConfig: PropertyCustodySecretConfig
  private lateinit var errors: Errors

  @BeforeEach
  fun setUp() {
    secretConfig = mockk()
    every { secretConfig.fireblocksApiKey } returns "testApiKey"
    every { secretConfig.fireblocksSecretKey } returns
      FileUtil.getResourceFileAsString("custody/fireblocks/client/secret_key.txt")
    config = FireblocksConfig(secretConfig)
    config.retryConfig = RetryConfig(3, 1000)
    config.baseUrl = "https://test.com"
    config.vaultAccountId = "testAccountId"
    config.reconciliation = Reconciliation(10, "* * * * * *")
    config.publicKey = FileUtil.getResourceFileAsString("custody/fireblocks/client/public_key.txt")
    errors = BindException(config, "config")
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty host_url`(url: String?) {
    config.baseUrl = url
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-base-url-empty")
  }

  @ParameterizedTest
  @ValueSource(strings = ["https://fireblocks.com", "https://fireblocks.org:8080"])
  fun `test valid host_url`(url: String) {
    config.baseUrl = url
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = ["https ://fireblocks.com", "fireblocks.com", "abc"])
  fun `test invalid host_url`(url: String) {
    config.baseUrl = url
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-base-url-invalid")
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty vault_account_id`(url: String?) {
    config.vaultAccountId = url
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-vault-account-id-empty")
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty api_key`(apiKey: String?) {
    every { secretConfig.fireblocksApiKey } returns apiKey
    config.validate(config, errors)
    assertErrorCode(errors, "secret-custody-fireblocks-api-key-empty")
  }

  @Test
  fun `test valid api_key`() {
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty secret_key`(secretKey: String?) {
    every { secretConfig.fireblocksSecretKey } returns secretKey
    config.validate(config, errors)
    assertErrorCode(errors, "secret-custody-fireblocks-secret-key-empty")
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      ["test_certificate", "-----BEGIN PRIVATE KEY----- test_certificate -----END PRIVATE KEY-----"]
  )
  fun `test invalid secret_key`(secretKey: String) {
    every { secretConfig.fireblocksSecretKey } returns secretKey
    config.validate(config, errors)
    assertErrorCode(errors, "secret-custody-fireblocks-secret_key-invalid")
  }

  @Test
  fun `test valid secret_key`() {
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = ["* * * * * *", "0 0/15 * * * *"])
  fun `test valid transactions_reconciliation_cron`(cron: String) {
    config.reconciliation.cronExpression = cron
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  fun `test empty transactions_reconciliation_cron`(cron: String) {
    config.reconciliation.cronExpression = cron
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-reconciliation-cron_expression-empty")
  }

  @ParameterizedTest
  @ValueSource(strings = ["* * * * *", "* * * * * * *", "0/a * * * * *"])
  fun `test invalid transactions_reconciliation_cron`(cron: String) {
    config.reconciliation.cronExpression = cron
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-reconciliation-cron_expression-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid reconciliation_max_attempts`(maxAttempts: Int) {
    config.reconciliation.maxAttempts = maxAttempts
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid reconciliation_max_attempts`(maxAttempts: Int) {
    config.reconciliation.maxAttempts = maxAttempts
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-reconciliation-max_attempts-invalid")
  }

  @Test
  fun `test valid public_key`() {
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  fun `test empty public_key`(publicKey: String) {
    config.publicKey = publicKey
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-public_key-empty")
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      ["test_certificate", "-----BEGIN PUBLIC KEY----- test_certificate -----END PUBLIC KEY-----"]
  )
  fun `test invalid public_key`(publicKey: String) {
    config.publicKey = publicKey
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-public_key-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid retry_config_max_attempts`(maxAttempts: Int) {
    config.retryConfig.maxAttempts = maxAttempts
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid retry_config_max_attempts`(maxAttempts: Int) {
    config.retryConfig.maxAttempts = maxAttempts
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-retry_config-max_attempts-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid retry_config_delay`(delay: Int) {
    config.retryConfig.delay = delay
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid retry_config_delay`(delay: Int) {
    config.retryConfig.delay = delay
    config.validate(config, errors)
    assertErrorCode(errors, "custody-fireblocks-retry_config-delay-invalid")
  }

  @ParameterizedTest
  @ValueSource(strings = [""])
  fun `test empty asset mappings`(mappings: String) {
    config.setAssetMappings(mappings)
    assertTrue(config.assetMappings.isEmpty())
  }

  @ParameterizedTest
  @ValueSource(strings = ["FIREBLOCKS_ASSET_CODE STELLAR_ASSET_CODE"])
  fun `test getFireblocksAssetCode`(mappings: String) {
    config.setAssetMappings(mappings)
    val stellarAssetCode = config.getFireblocksAssetCode("STELLAR_ASSET_CODE")
    assertEquals("FIREBLOCKS_ASSET_CODE", stellarAssetCode)

    assertThrows<InvalidConfigException> { config.getFireblocksAssetCode("INVALID_ASSET_CODE") }
  }
}
