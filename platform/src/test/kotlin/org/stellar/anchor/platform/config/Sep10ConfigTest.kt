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
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.config.ClientsConfig.ClientConfig
import org.stellar.anchor.config.ClientsConfig.ClientType.CUSTODIAL
import org.stellar.anchor.config.ClientsConfig.ClientType.NONCUSTODIAL
import org.stellar.anchor.platform.utils.setupMock

class Sep10ConfigTest {
  lateinit var config: PropertySep10Config
  lateinit var errors: Errors
  private lateinit var secretConfig: PropertySecretConfig
  private lateinit var appConfig: AppConfig
  private var clientsConfig = PropertyClientsConfig()

  @BeforeEach
  fun setUp() {
    secretConfig = mockk()
    appConfig = mockk()

    clientsConfig.clients.add(
      ClientConfig.builder()
        .name("unknown")
        .type(CUSTODIAL)
        .signingKeys(setOf("GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2"))
        .build()
    )

    clientsConfig.clients.add(
      ClientConfig.builder()
        .name("lobstr")
        .type(NONCUSTODIAL)
        .signingKeys(setOf("GC4HAYCFQYQLJV5SE6FB3LGC37D6XGIXGMAXCXWNBLH7NWW2JH4OZLHQ"))
        .domains(setOf("lobstr.co"))
        .callbackUrl("https://callback.lobstr.co/api/v2/anchor/callback")
        .build()
    )

    clientsConfig.clients.add(
      ClientConfig.builder()
        .name("circle")
        .type(NONCUSTODIAL)
        .signingKeys(setOf("GCSGSR6KQQ5BP2FXVPWRL6SWPUSFWLVONLIBJZUKTVQB5FYJFVL6XOXE"))
        .domains(setOf("circle.com"))
        .callbackUrl("https://callback.circle.com/api/v2/anchor/callback")
        .build()
    )

    config = PropertySep10Config(appConfig, clientsConfig, secretConfig)
    config.enabled = true
    config.homeDomain = "stellar.org"
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
    val config = PropertySep10Config(appConfig, PropertyClientsConfig(), secretConfig)
    config.isClientAttributionRequired = true
    config.validateClientAttribution(errors)
    assertErrorCode(errors, "sep10-client-attribution-lists-empty")
  }

  @Test
  fun `test ClientsConfig getClientConfigByName`() {
    assertEquals(clientsConfig.getClientConfigByName("unknown"), clientsConfig.clients[0])
    assertEquals(clientsConfig.getClientConfigByName("lobstr"), clientsConfig.clients[1])
    assertEquals(clientsConfig.getClientConfigByName("circle"), clientsConfig.clients[2])
  }

  @Test
  fun `test ClientsConfig getClientConfigByDomain`() {
    assertEquals(
      null,
      clientsConfig.getClientConfigByDomain("unknown"),
    )
    assertEquals(clientsConfig.clients[1], clientsConfig.getClientConfigByDomain("lobstr.co"))
    assertEquals(clientsConfig.clients[2], clientsConfig.getClientConfigByDomain("circle.com"))
  }

  @Test
  fun `test ClientsConfig getClientConfigBySigningKey`() {
    assertEquals(clientsConfig.getClientConfigBySigningKey("unknown"), null)
    assertEquals(
      clientsConfig.clients[1],
      clientsConfig.getClientConfigBySigningKey(
        "GC4HAYCFQYQLJV5SE6FB3LGC37D6XGIXGMAXCXWNBLH7NWW2JH4OZLHQ"
      )
    )
    assertEquals(
      clientsConfig.clients[2],
      clientsConfig.getClientConfigBySigningKey(
        "GCSGSR6KQQ5BP2FXVPWRL6SWPUSFWLVONLIBJZUKTVQB5FYJFVL6XOXE"
      )
    )
  }

  @Test
  fun `test when clientAllowList is not defined, clientAttributionAllowList equals to the list of all clients`() {
    val config = PropertySep10Config(appConfig, clientsConfig, secretConfig)
    assertEquals(config.allowedClientDomains, listOf("lobstr.co", "circle.com"))
  }

  @Test
  fun `test when clientAllowList is defined, clientAttributionAllowList returns correct values`() {
    val config = PropertySep10Config(appConfig, clientsConfig, secretConfig)
    config.clientAllowList = listOf("lobstr")
    assertEquals(config.allowedClientDomains, listOf("lobstr.co"))

    config.clientAllowList = listOf("circle")
    assertEquals(config.allowedClientDomains, listOf("circle.com"))

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
    config.homeDomain = value
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
    config.homeDomain = value
    config.validateConfig(errors)
    assertTrue(errors.hasErrors())
    assertErrorCode(errors, expectedErrorCode)
  }

  @Test
  fun `test if web_auth_domain is not set, default to the domain of the host_url`() {
    config.webAuthDomain = null
    config.homeDomain = "www.stellar.org"
    config.postConstruct()
    assertEquals("www.stellar.org", config.webAuthDomain)
  }

  @Test
  fun `test if web_auth_domain is set, it is not default to the domain of the host_url`() {
    config.webAuthDomain = "localhost:8080"
    config.homeDomain = "www.stellar.org"
    config.postConstruct()
    assertEquals("localhost:8080", config.webAuthDomain)
  }

  @ParameterizedTest
  @MethodSource("generatedHomeDomainsTestConfig")
  fun `test web_auth_domain, home_domain and home_domains in valid config format`(
    webAuthDomain: String?,
    homeDomain: String?,
    homeDomains: List<String>?,
    hasError: Boolean,
    numberOfHomeDomains: Int
  ) {
    config.webAuthDomain = webAuthDomain
    config.homeDomain = homeDomain
    config.homeDomains = homeDomains

    config.validateConfig(errors)
    assertEquals(hasError, errors.hasErrors())

    if (!hasError) {
      config.postConstruct()
      assertEquals(numberOfHomeDomains, config.homeDomains.size)
    }
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
        Arguments.of(null, null, null, false, 1),
        Arguments.of(null, "www.stellar.org", listOf("www.stellar.org", "www.losbstr.co"), true, 0),
        Arguments.of(null, "www.stellar.org", emptyList<String>(), false, 1),
        Arguments.of("localhost:8080", "", listOf("www.stellar.org", "www.losbstr.co"), false, 2),
        Arguments.of("localhost:8080", "", listOf("*.stellar.org"), false, 1),
        Arguments.of("", "", listOf("*.stellar.org"), true, 1),
      )
    }
  }
}
