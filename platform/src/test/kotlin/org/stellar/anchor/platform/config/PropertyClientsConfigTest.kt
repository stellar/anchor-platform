package org.stellar.anchor.platform.config

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.config.ClientsConfig.ClientConfig
import org.stellar.anchor.config.ClientsConfig.ClientType.CUSTODIAL
import org.stellar.anchor.config.ClientsConfig.ClientType.NONCUSTODIAL

class PropertyClientsConfigTest {
  private lateinit var configs: PropertyClientsConfig
  private lateinit var errors: Errors

  @BeforeEach
  fun setUp() {
    configs = PropertyClientsConfig()
    errors = BindException(configs, "config")
  }

  @Test
  fun `test postConstruct`() {
    val config = ClientConfig()
    config.name = "sampleName"
    config.signingKey = "sampleSigningKey"
    config.domain = "sampleDomain"

    configs.clients.add(config)
    configs.postConstruct()

    Assertions.assertEquals(1, config.signingKeys.size)
    Assertions.assertEquals(config.signingKey, config.signingKeys.first())
    Assertions.assertEquals(1, config.signingKeys.size)
    Assertions.assertEquals(config.domain, config.domains.first())
  }

  @Test
  fun `test valid custodial client with multiple signing keys`() {
    val config = ClientConfig()
    config.name = "circle"
    config.type = CUSTODIAL
    config.signingKeys =
      setOf(
        "GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2",
        "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F"
      )
    config.callbackUrl = "https://callback.circle.com/api/v1/anchor/callback"
    configs.clients.add(config)

    configs.validate(configs, errors)
    Assertions.assertFalse(errors.hasErrors())

    val getConfigResult1 =
      configs.getClientConfigBySigningKey(
        "GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2"
      )
    Assertions.assertEquals(config, getConfigResult1)

    val getConfigResult2 =
      configs.getClientConfigBySigningKey(
        "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F"
      )
    Assertions.assertEquals(config, getConfigResult2)
  }

  @Test
  fun `test invalid custodial client with empty signing key`() {
    val config = ClientConfig()
    config.signingKey = ""
    config.signingKeys = emptySet()
    configs.clients.add(config)

    configs.validateCustodialClient(config, errors)
    Assertions.assertEquals(1, errors.errorCount)
    assertErrorCode(errors, "empty-client-signing-keys")
  }

  @Test
  fun `test invalid custodial client with both signing key and signing keys`() {
    val config = ClientConfig()
    config.signingKey = "GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2"
    config.signingKeys =
      setOf(
        "GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2",
        "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F"
      )
    configs.clients.add(config)

    configs.validateCustodialClient(config, errors)
    Assertions.assertEquals(1, errors.errorCount)
    assertErrorCode(errors, "client-signing-keys-conflict")
  }

  @Test
  fun `test valid non-custodial client with multiple domains`() {
    val config = ClientConfig()
    config.name = "lobstr"
    config.type = NONCUSTODIAL
    config.domains = setOf("lobstr.co", "lobstr.com")
    config.callbackUrl = "https://callback.lobstr.co/api/v2/anchor/callback"
    configs.clients.add(config)

    configs.validate(configs, errors)
    Assertions.assertFalse(errors.hasErrors())

    val getConfigResult1 = configs.getClientConfigByDomain("lobstr.co")
    Assertions.assertEquals(config, getConfigResult1)

    val getConfigResult2 = configs.getClientConfigByDomain("lobstr.com")
    Assertions.assertEquals(config, getConfigResult2)
  }

  @Test
  fun `test valid non-custodial client with all callback URLs set`() {
    val config = ClientConfig()
    config.name = "circle"
    config.type = NONCUSTODIAL
    config.domains = setOf("circle.com")
    config.callbackUrl = "https://callback.circle.com/api/v1/anchor/callback"
    config.callbackUrlSep6 = "https://callback.circle.com/api/v1/anchor/callback/sep6"
    config.callbackUrlSep24 = "https://callback.circle.com/api/v1/anchor/callback/sep24"
    config.callbackUrlSep31 = "https://callback.circle.com/api/v1/anchor/callback/sep31"
    config.callbackUrlSep12 = "https://callback.circle.com/api/v1/anchor/callback/sep12"
    configs.clients.add(config)

    configs.validate(configs, errors)
    Assertions.assertFalse(errors.hasErrors())
  }

  @Test
  fun `test valid custodial client with all callback URLs set`() {
    val config = ClientConfig()
    config.name = "circle"
    config.type = CUSTODIAL
    config.signingKeys =
      setOf(
        "GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2",
        "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F"
      )
    config.callbackUrl = "https://callback.circle.com/api/v1/anchor/callback"
    config.callbackUrlSep6 = "https://callback.circle.com/api/v1/anchor/callback/sep6"
    config.callbackUrlSep24 = "https://callback.circle.com/api/v1/anchor/callback/sep24"
    config.callbackUrlSep31 = "https://callback.circle.com/api/v1/anchor/callback/sep31"
    config.callbackUrlSep12 = "https://callback.circle.com/api/v1/anchor/callback/sep12"
    configs.clients.add(config)

    configs.validate(configs, errors)
    Assertions.assertFalse(errors.hasErrors())
  }

  @Test
  fun `test invalid non-custodial client with invalid callback URLs`() {
    val config = ClientConfig()
    config.name = "lobstr"
    config.type = NONCUSTODIAL
    config.domains = setOf("lobstr.co")
    config.callbackUrl = "bad-url"
    config.callbackUrlSep6 = "bad-url"
    config.callbackUrlSep24 = "bad-url"
    config.callbackUrlSep31 = "bad-url"
    config.callbackUrlSep12 = "bad-url"
    configs.clients.add(config)

    configs.validate(configs, errors)
    Assertions.assertEquals(5, errors.errorCount)
  }

  @Test
  fun `test invalid custodial client with invalid callback URLs`() {
    val config = ClientConfig()
    config.name = "circle"
    config.type = CUSTODIAL
    config.signingKeys =
      setOf(
        "GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2",
        "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F"
      )
    config.callbackUrl = "bad-url"
    config.callbackUrlSep6 = "bad-url"
    config.callbackUrlSep24 = "bad-url"
    config.callbackUrlSep31 = "bad-url"
    config.callbackUrlSep12 = "bad-url"
    configs.clients.add(config)

    configs.validate(configs, errors)
    Assertions.assertEquals(5, errors.errorCount)
  }

  @Test
  fun `test invalid non-custodial client with empty domain and callback url`() {
    val config = ClientConfig()
    config.domain = ""
    config.domains = emptySet()
    config.callbackUrl = "  "
    configs.clients.add(config)

    configs.validateNonCustodialClient(config, errors)
    Assertions.assertEquals(2, errors.errorCount)
  }

  @Test
  fun `test invalid non-custodial client with both domain and domains`() {
    val config = ClientConfig()
    config.domain = "lobstr.co"
    config.domains = setOf("lobstr.co", "lobstr.com")
    configs.clients.add(config)

    configs.validateNonCustodialClient(config, errors)
    Assertions.assertEquals(1, errors.errorCount)
    assertErrorCode(errors, "client-domains-conflict")
  }
}
