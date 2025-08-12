package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.*
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.client.ClientService
import org.stellar.anchor.client.DefaultClientService
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.platform.utils.setupMock

class Sep10ConfigTest {
  lateinit var config: PropertySep10Config
  lateinit var errors: Errors
  private lateinit var secretConfig: PropertySecretConfig
  private lateinit var stellarNetworkConfig: StellarNetworkConfig
  private lateinit var clientService: ClientService

  @BeforeEach
  fun setUp() {
    secretConfig = mockk()
    stellarNetworkConfig = mockk()
    clientService = DefaultClientService.fromYamlResourceFile("test_clients.yaml")

    config = PropertySep10Config(stellarNetworkConfig, clientService, secretConfig)
    config.enabled = true
    config.homeDomains = listOf("stellar.org")
    errors = BindException(config, "config")
    secretConfig.setupMock()
  }

  @Test
  fun `test default sep10 config`() {
    config.validateConfig(errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test client attribution and lists`() {
    config.isClientAttributionRequired = true
    config.validateClientAttribution(errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test validation of empty client allow list when client attribution is required`() {
    val config = PropertySep10Config(stellarNetworkConfig, DefaultClientService(), secretConfig)
    config.isClientAttributionRequired = true
    config.validateClientAttribution(errors)
    assertErrorCode(errors, "sep10-client-attribution-lists-empty")
  }

  @Test
  fun `test ClientService getClientConfigByName`() {
    assertEquals(
      clientService.custodialClients[0],
      clientService.getClientConfigByName("some-wallet"),
    )
    assertEquals(
      clientService.nonCustodialClients[0],
      clientService.getClientConfigByName("lobstr")
    )
    assertEquals(
      clientService.nonCustodialClients[1],
      clientService.getClientConfigByName("circle")
    )
  }

  @Test
  fun `test ClientService getClientConfigByDomain`() {
    assertEquals(
      null,
      clientService.getClientConfigByDomain("unknown"),
    )
    assertEquals(
      clientService.nonCustodialClients[0],
      clientService.getClientConfigByDomain("lobstr.co")
    )
    assertEquals(
      clientService.nonCustodialClients[1],
      clientService.getClientConfigByDomain("circle.com")
    )
  }

  @Test
  fun `test ClientsConfig getClientConfigBySigningKey`() {
    assertEquals(clientService.getClientConfigBySigningKey("unknown"), null)
    assertEquals(
      clientService.custodialClients[0],
      clientService.getClientConfigBySigningKey(
        "GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2"
      )
    )
  }

  @Test
  fun `test when clientAllowList is not defined, clientAttributionAllowList equals to the list of all clients`() {
    val config = PropertySep10Config(stellarNetworkConfig, clientService, secretConfig)
    assertEquals(config.allowedClientDomains, listOf("lobstr.co", "circle.com"))
  }

  @Test
  fun `test when clientAllowList is defined, clientAttributionAllowList returns correct values`() {
    val config = PropertySep10Config(stellarNetworkConfig, clientService, secretConfig)
    config.clientAllowList = listOf("lobstr")
    assertEquals(config.allowedClientDomains, listOf("lobstr.co"))

    config.clientAllowList = listOf("circle")
    assertEquals(listOf("circle.com"), config.allowedClientDomains)

    config.clientAllowList = listOf("invalid")
    config.validateClientAttribution(errors)
    assertErrorCode(errors, "sep10-client-allow-list-invalid")
    assertTrue(config.allowedClientDomains.isEmpty())
  }

  @Test
  fun `test required known custodial account`() {
    config.validateCustodialAccounts(errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = ["stellar.org", "moneygram.com", "localhost", "127.0.0.1:80"])
  fun `test valid home domains`(value: String) {
    config.webAuthDomain = value
    config.homeDomains = listOf(value)
    config.validateConfig(errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = ["bad key", "GCS2KBEGIWILNKFYY6ORT72Y2HUFYG6IIIOESHVQC3E5NIYT3L2I5F5E"])
  fun `test invalid sep10 seeds`(value: String?) {
    every { secretConfig.sep10SigningSeed } returns value
    config.validateConfig(errors)
    assertTrue(errors.hasErrors())
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "this-is-longer-than-64-bytes-which-is-the-maximum-length-for-a-web-auth-domain.stellar.org,sep10-web-auth-domain-too-long",
        "stellar.org:1000:1000,sep10-web-auth-domain-invalid",
      ]
  )
  fun `test invalid web auth domains`(value: String, expectedErrorCode: String) {
    config.webAuthDomain = value
    config.validateConfig(errors)
    assertTrue(errors.hasErrors())
    assertErrorCode(errors, expectedErrorCode)
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "this-is-longer-than-64-bytes-which-is-the-maximum-length-for-a-home-domain.stellar.org,sep10-home-domain-too-long",
        "http://stellar.org,sep10-home-domain-invalid",
        "https://stellar.org,sep10-home-domain-invalid",
        "://stellar.org,sep10-home-domain-invalid",
      ]
  )
  fun `test invalid home domains`(value: String, expectedErrorCode: String) {
    config.homeDomains = listOf(value)
    config.validateConfig(errors)
    assertTrue(errors.hasErrors())
    assertErrorCode(errors, expectedErrorCode)
  }

  @Test
  fun `test if web_auth_domain is not set, default to the domain of the host_url`() {
    config.webAuthDomain = null
    config.homeDomains = listOf("www.stellar.org")
    config.postConstruct()
    assertEquals("www.stellar.org", config.webAuthDomain)
  }

  @Test
  fun `test if web_auth_domain is set, it is not default to the domain of the host_url`() {
    config.webAuthDomain = "localhost:8080"
    config.homeDomains = listOf("www.stellar.org")
    config.postConstruct()
    assertEquals("localhost:8080", config.webAuthDomain)
  }

  @ParameterizedTest
  @MethodSource("generatedHomeDomainsTestConfig")
  fun `test web_auth_domain and home_domains in valid config format`(
    webAuthDomain: String?,
    homeDomains: List<String>?,
    hasError: Boolean,
  ) {
    config.webAuthDomain = webAuthDomain
    config.homeDomains = homeDomains

    config.validateConfig(errors)
    assertEquals(hasError, errors.hasErrors())
  }

  @Test
  fun `validate JWT`() {
    every { secretConfig.sep10JwtSecretKey }.returns("tooshort")
    config.validateConfig(errors)
    assertTrue(errors.hasErrors())
    assertErrorCode(errors, "hmac-weak-secret")
  }

  companion object {
    @JvmStatic
    fun generatedHomeDomainsTestConfig(): Stream<Arguments> {
      return Stream.of(
        Arguments.of(null, null, true),
        Arguments.of(null, listOf("www.stellar.org", "www.losbstr.co"), true),
        Arguments.of(null, emptyList<String>(), true),
        Arguments.of("localhost:8080", listOf("www.stellar.org", "www.losbstr.co"), false),
        Arguments.of("localhost:8080", listOf("*.stellar.org"), false),
        Arguments.of("", listOf("*.stellar.org"), true),
      )
    }
  }
}
