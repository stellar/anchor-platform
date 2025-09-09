package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.config.RpcAuthConfig
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.StellarNetworkConfig.ProviderType.HORIZON
import org.stellar.anchor.config.StellarNetworkConfig.ProviderType.RPC

class StellarNetworkConfigTest {
  private lateinit var config: PropertyStellarNetworkConfig
  private lateinit var errors: Errors
  private val secretConfig: SecretConfig = mockk()

  @BeforeEach
  fun setUp() {
    config = PropertyStellarNetworkConfig(secretConfig)
    config.type = HORIZON
    config.network = "TESTNET"
    config.horizonUrl = "https://horizon-testnet.stellar.org"
    errors = BindException(config, "config")
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty horizon_url`(url: String?) {
    config.horizonUrl = url
    config.validateConfig(config, errors)
    assertErrorCode(errors, "horizon-url-empty")
  }

  @Test
  fun `test empty header auth type`() {
    config.type = RPC
    config.rpcUrl = "https://soroban-testnet.stellar.org"
    config.rpcAuth =
      RpcAuthConfig().apply {
        type = RpcAuthConfig.RpcAuthType.HEADER
        headerConfig = RpcAuthConfig.HeaderConfig("")
      }
    every { secretConfig.rpcAuthSecret } returns "secret"

    config.validate(config, errors)
    assertErrorCode(errors, "rpc-auth-header-name-empty")
  }

  @ParameterizedTest
  @ValueSource(
    strings = ["https://horizon-testnet.stellar.org", "https://horizon-testnet.stellar.org:8080"]
  )
  fun `test valid horizon_url`(url: String) {
    config.horizonUrl = url
    config.validateConfig(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = ["https ://horizon-testnet.stellar. org", "stellar.org", "abc"])
  fun `test invalid horizon_url`(url: String) {
    config.horizonUrl = url
    config.validateConfig(config, errors)
    assertErrorCode(errors, "horizon-url-invalid")
  }

  @ParameterizedTest
  @ValueSource(strings = ["https ://soroban-testnet.stellar.org", "stellar.org", "abc"])
  fun `test invalid rpc_url`(url: String) {
    config.type = RPC
    config.rpcUrl = url
    config.validateConfig(config, errors)
    assertErrorCode(errors, "rpc-url-invalid")
  }

  @ParameterizedTest
  @ValueSource(strings = ["TESTNET", "testnet", "testNET", "PUBLIC", "public", "PUBlic"])
  fun `test valid stellar network configurations`(network: String) {
    config.network = network
    config.validateConfig(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = ["TESTNET1", "mainnet", ""])
  fun `test invalid stellar network configurations`(network: String) {
    config.network = network
    assertThrows<RuntimeException> { config.validateConfig(config, errors) }
  }

  @Test
  fun `test valid and empty secret config`() {
    config.type = RPC
    config.rpcUrl = "https://soroban-testnet.stellar.org"
    config.rpcAuth =
      RpcAuthConfig().apply {
        type = RpcAuthConfig.RpcAuthType.HEADER
        headerConfig = RpcAuthConfig.HeaderConfig("Authorization")
      }

    every { secretConfig.rpcAuthSecret } returns "secret"
    config.validate(config, errors)
    assertFalse(errors.hasErrors())

    errors = BindException(config, "config")
    every { secretConfig.rpcAuthSecret } returns ""
    config.validate(config, errors)
    assertErrorCode(errors, "rpc-auth-secret-empty")
  }
}
