package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.platform.utils.setupMock

class Sep45ConfigTest {
  lateinit var config: PropertySep45Config
  lateinit var errors: Errors
  private lateinit var secretConfig: PropertySecretConfig
  private lateinit var stellarNetworkConfig: StellarNetworkConfig

  @BeforeEach
  fun setup() {
    secretConfig = mockk()
    stellarNetworkConfig = mockk()

    every { stellarNetworkConfig.rpcUrl } returns "https://soroban-testnet.stellar.org"
    every { secretConfig.sep45JwtSecretKey } returns "some_jwt_secret"

    config = PropertySep45Config(stellarNetworkConfig, secretConfig)
    config.enabled = true
    config.webAuthDomain = "stellar.org"
    config.webAuthContractId = "CAASCQKVVBSLREPEUGPOTQZ4BC2NDBY2MW7B2LGIGFUPIY4Z3XUZRVTX"
    config.homeDomains = listOf("stellar.org")
    config.jwtTimeout = 86400
    config.authTimeout = 900
    errors = BindException(config, "config")
    secretConfig.setupMock()
  }

  @Test
  fun `test default sep45 config`() {
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = ["stellar.org", "localhost", "127.0.0.1:80"])
  fun `test valid home domains`(value: String) {
    config.webAuthDomain = value
    config.homeDomains = listOf(value)
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test missing rpc url`() {
    every { stellarNetworkConfig.rpcUrl } returns null
    config.validate(config, errors)
    assertEquals("stellar-network-rpc-url-empty", errors.allErrors[0].code)
  }

  @Test
  fun `test missing sep45 jwt secret key`() {
    every { secretConfig.sep45JwtSecretKey } returns null
    config.validate(config, errors)
    assertEquals("sep45-jwt-secret-empty", errors.allErrors[0].code)
  }

  @Test
  fun `test missing sep45 web auth contract id`() {
    config.webAuthContractId = null
    config.validate(config, errors)
    assertEquals("sep45-web-auth-contract-id-empty", errors.allErrors[0].code)
  }

  @Test
  fun `test invalid sep45 web auth contract id`() {
    config.webAuthContractId = "CAAABAD"
    config.validate(config, errors)
    assertEquals("sep45-web-auth-contract-id-invalid", errors.allErrors[0].code)
  }

  @Test
  fun `test if web_auth_domain is not set, default to the domain of the host_url`() {
    config.webAuthDomain = null
    config.homeDomains = listOf("www.stellar.org")
    config.postConstruct()
    Assertions.assertEquals("www.stellar.org", config.webAuthDomain)
  }

  @Test
  fun `test if web_auth_domain is set, it is not default to the domain of the host_url`() {
    config.webAuthDomain = "localhost:8080"
    config.homeDomains = listOf("www.stellar.org")
    config.postConstruct()
    Assertions.assertEquals("localhost:8080", config.webAuthDomain)
  }
}
