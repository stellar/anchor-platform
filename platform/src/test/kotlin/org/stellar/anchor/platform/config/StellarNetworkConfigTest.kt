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

  private fun useRpc(url: String?) {
    config.type = RPC
    config.rpcUrl = url
    config.rpcAuth = RpcAuthConfig().apply { type = RpcAuthConfig.RpcAuthType.NONE }
  }

  private val insecureUrls =
    listOf(
      "http://horizon-testnet.stellar.org",
      "http://192.168.100.1:8000",
      "ftp://horizon.example",
      "file:///etc/passwd",
      "http://localhost@evil.example",
      "http://localhost.evil.example",
      "http://127.0.0.1.evil.example",
    )

  private val secureUrls =
    listOf(
      "https://horizon-testnet.stellar.org",
      "HTTPS://Horizon.Stellar.Org",
      "http://localhost:8000",
      "http://LOCALHOST",
      "http://127.0.0.1:8000/path",
      "http://[::1]:8000",
    )

  @Test
  fun `test insecure horizon_url is rejected with horizon-url-insecure`() {
    insecureUrls.forEach { url ->
      errors = BindException(config, "config")
      config.horizonUrl = url
      config.validateConfig(config, errors)
      assertEquals(1, errors.errorCount, url)
      assertEquals("horizon-url-insecure", errors.getFieldError("horizonUrl")?.code, url)
    }
  }

  @Test
  fun `test https and loopback http horizon_url are accepted`() {
    secureUrls.forEach { url ->
      errors = BindException(config, "config")
      config.horizonUrl = url
      config.validateConfig(config, errors)
      assertFalse(errors.hasErrors(), url)
    }
  }

  @Test
  fun `test insecure rpc_url is rejected with rpc-url-insecure when type is RPC`() {
    insecureUrls.forEach { url ->
      errors = BindException(config, "config")
      useRpc(url)
      config.validateConfig(config, errors)
      assertEquals(1, errors.errorCount, url)
      assertEquals("rpc-url-insecure", errors.getFieldError("rpcUrl")?.code, url)
    }
  }

  @Test
  fun `test insecure rpc_url is rejected when type is HORIZON`() {
    insecureUrls.forEach { url ->
      errors = BindException(config, "config")
      config.type = HORIZON
      config.rpcUrl = url
      config.validateConfig(config, errors)
      assertEquals(1, errors.errorCount, url)
      assertEquals("rpc-url-insecure", errors.getFieldError("rpcUrl")?.code, url)
    }
  }

  @Test
  fun `test https and loopback http rpc_url are accepted`() {
    secureUrls.forEach { url ->
      errors = BindException(config, "config")
      useRpc(url)
      config.validateConfig(config, errors)
      assertFalse(errors.hasErrors(), url)
    }
  }

  @Test
  fun `test horizon_url is not checked when type is RPC`() {
    config.horizonUrl = "http://horizon-testnet.stellar.org"
    useRpc("https://soroban-testnet.stellar.org")
    config.validateConfig(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty rpc_url adds no error when type is HORIZON`(url: String?) {
    config.rpcUrl = url
    config.validateConfig(config, errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test empty and malformed urls keep their existing error and no insecure error`() {
    config.horizonUrl = "abc"
    config.validateConfig(config, errors)
    assertEquals(1, errors.errorCount)
    assertErrorCode(errors, "horizon-url-invalid")

    errors = BindException(config, "config")
    config.horizonUrl = ""
    config.validateConfig(config, errors)
    assertEquals(1, errors.errorCount)
    assertErrorCode(errors, "horizon-url-empty")

    errors = BindException(config, "config")
    useRpc("stellar.org")
    config.validateConfig(config, errors)
    assertEquals(1, errors.errorCount)
    assertErrorCode(errors, "rpc-url-invalid")

    errors = BindException(config, "config")
    useRpc("")
    config.validateConfig(config, errors)
    assertEquals(1, errors.errorCount)
    assertErrorCode(errors, "rpc-url-empty")
  }

  @Test
  fun `test insecure error names the key and https but never echoes the url`() {
    val secretUrl = "http://user:SECRET@host.example/path?key=abc123"

    config.horizonUrl = secretUrl
    config.validateConfig(config, errors)
    val horizonMessage = errors.getFieldError("horizonUrl")!!.defaultMessage!!
    assertTrue(horizonMessage.contains("stellar_network.horizon_url"), horizonMessage)
    assertTrue(horizonMessage.contains("https"), horizonMessage)
    assertFalse(horizonMessage.contains("SECRET"), horizonMessage)
    assertFalse(horizonMessage.contains("abc123"), horizonMessage)
    assertFalse(horizonMessage.contains("host.example"), horizonMessage)

    errors = BindException(config, "config")
    useRpc(secretUrl)
    config.validateConfig(config, errors)
    val rpcMessage = errors.getFieldError("rpcUrl")!!.defaultMessage!!
    assertTrue(rpcMessage.contains("stellar_network.rpc_url"), rpcMessage)
    assertTrue(rpcMessage.contains("https"), rpcMessage)
    assertFalse(rpcMessage.contains("SECRET"), rpcMessage)
    assertFalse(rpcMessage.contains("abc123"), rpcMessage)
    assertFalse(rpcMessage.contains("host.example"), rpcMessage)
  }

  @Test
  fun `test validate records the insecure error so startup fails`() {
    config.horizonUrl = "http://horizon-testnet.stellar.org"
    config.rpcAuth = RpcAuthConfig().apply { type = RpcAuthConfig.RpcAuthType.NONE }
    config.validate(config, errors)
    assertTrue(errors.hasErrors())
    assertErrorCode(errors, "horizon-url-insecure")

    errors = BindException(config, "config")
    useRpc("http://soroban-testnet.stellar.org")
    config.validate(config, errors)
    assertTrue(errors.hasErrors())
    assertErrorCode(errors, "rpc-url-insecure")
  }
}
