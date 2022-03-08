package org.stellar.anchor.platform.configurator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.get
import org.springframework.test.context.ContextConfiguration
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.config.Sep1Config
import org.stellar.anchor.config.Sep38Config
import org.stellar.anchor.platform.ConfigManagementConfig

@Configuration
@EnableConfigurationProperties
@Import(ConfigManagementConfig::class)
open class SpringBootTestConfiguration

internal class SystemPropertyInitializer :
  ApplicationContextInitializer<ConfigurableApplicationContext> {
  override fun initialize(applicationContext: ConfigurableApplicationContext) {
    System.setProperty("stellar.anchor.config", "classpath:test-anchor-config.yaml")
  }
}

@SpringBootTest
@ContextConfiguration(
  classes = [SpringBootTestConfiguration::class],
  initializers =
    [
      SystemPropertyInitializer::class,
      PropertiesReader::class,
      PlatformAppConfigurator::class,
      DataAccessConfigurator::class,
      SpringFrameworkConfigurator::class]
)
open class SpringBootConfiguratorTest {
  @Autowired lateinit var context: ConfigurableApplicationContext
  @Autowired lateinit var appConfig: AppConfig
  @Autowired lateinit var sep1Config: Sep1Config
  @Autowired lateinit var sep10Config: Sep10Config
  @Autowired lateinit var sep38Config: Sep38Config

  @Test
  fun testYamlProperties() {
    val tests =
      mapOf(
        "sep1.enabled" to "true",
        "sep10.enabled" to "true",
        "sep10.homeDomain" to "localhost:8080",
        "sep10.signingSeed" to "SAX3AH622R2XT6DXWWSRIDCMMUCCMATBZ5U6XKJWDO7M2EJUBFC3AW5X",
        "sep38.enabled" to "true",
        "sep38.quoteIntegrationEndPoint" to "localhost:8082",
        "payment-gateway.circle.name" to "circle",
        "payment-gateway.circle.stellarNetwork" to "TESTNET",
        "spring.jpa.properties.hibernate.dialect" to "org.hibernate.dialect.H2Dialect",
        "logging.level.root" to "INFO",
        "server.servlet.context-path" to "/"
      )

    tests.forEach { assertEquals(it.value, context.environment[it.key]) }
  }

  @Test
  fun testAppConfig() {
    assertEquals("Test SDF Network ; September 2015", appConfig.stellarNetworkPassphrase)
    assertEquals("http://localhost:8080", appConfig.hostUrl)
    assertEquals(listOf("en"), appConfig.languages)
    assertEquals("https://horizon-testnet.stellar.org", appConfig.horizonUrl)
    assertEquals("assets-test.json", appConfig.assets)
    assertEquals("secret", appConfig.jwtSecretKey)
  }

  @Test
  fun testSep1Config() {
    assertEquals(true, sep1Config.isEnabled)
    assertEquals("sep1/stellar-wks.toml", sep1Config.stellarFile)
  }

  @Test
  fun testSep10Config() {
    assertEquals(true, sep10Config.enabled)
    assertEquals("localhost:8080", sep10Config.homeDomain)
    assertEquals(false, sep10Config.isClientAttributionRequired)
    assertEquals(listOf("lobstr.co", "preview.lobstr.co"), sep10Config.clientAttributionAllowList)
    assertEquals(900, sep10Config.authTimeout)
    assertEquals(86400, sep10Config.jwtTimeout)
    assertEquals(
      "SAX3AH622R2XT6DXWWSRIDCMMUCCMATBZ5U6XKJWDO7M2EJUBFC3AW5X",
      sep10Config.signingSeed
    )
  }

  @Test
  fun testSep38Config() {
    assertEquals(true, sep38Config.isEnabled)
    assertEquals("localhost:8082", sep38Config.quoteIntegrationEndPoint)
  }
}
