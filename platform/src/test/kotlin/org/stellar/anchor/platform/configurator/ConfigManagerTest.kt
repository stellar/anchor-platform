package org.stellar.anchor.platform.configurator

import io.mockk.*
import kotlin.test.assertNull
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.ClassPathResource
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.platform.configurator.ConfigMap.ConfigSource.DEFAULT
import org.stellar.anchor.platform.configurator.ConfigMap.ConfigSource.FILE

class ConfigManagerTest {
  val configManager =
    spyk(
      object : ConfigManager() {
        override fun initialize(context: ConfigurableApplicationContext) {}
      }
    )
  @Test
  fun `test reading a file and it is processed correctly`() {
    val testingConfigFile = ClassPathResource("config/test_anchor_config.yaml")
    every { configManager.getConfigFileAsResource(any()) } returns testingConfigFile

    val config = configManager.processConfigurations(null)
    assertEquals(config.get("languages").value, "tw, en, fr")
    assertEquals(config.get("clients[0].name").value, "vibrant")
    assertEquals(config.get("clients[0].domain").value, "vibrant.co")
    assertEquals(
      config.get("clients[0].callback_url").value,
      "https://callback.vibrant.com/api/v2/anchor/callback"
    )
    assertEquals(
      config.get("clients[0].signing_key").value,
      "GA22WORKYRXB6AW7XR5GIOAOQUY4KKCENEAI34FN3KJNWHKDZTZSVLTU"
    )

    assertEquals(config.get("clients[1].name").value, "lobstr")
    assertEquals(config.get("clients[1].type").value, "noncustodial")
    assertEquals(config.get("clients[1].domain").value, "lobstr.co")
    assertEquals(
      config.get("clients[1].callback_url").value,
      "https://callback.lobstr.co/api/v2/anchor/callback"
    )
  }
  @Test
  fun `test reading a file missing version throws an exception`() {
    val testingConfigFile = ClassPathResource("config/test_anchor_config_missing_version.yaml")
    every { configManager.getConfigFileAsResource(any()) } returns testingConfigFile

    val ex =
      org.junit.jupiter.api.assertThrows<IllegalStateException> {
        configManager.processConfigurations(null)
      }
    assertEquals(
      ex.message,
      "java.io.FileNotFoundException: class path resource [config/anchor-config-schema-v0.yaml] cannot be opened because it does not exist"
    )
  }
}

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ConfigManagerTestExt {
  private lateinit var configManager: ConfigManager

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    mockkStatic(ConfigReader::class)
    mockkStatic(ConfigHelper::class)

    configManager = spyk(ConfigManager.getInstance())

    every { ConfigHelper.loadDefaultConfig() } returns
      ConfigHelper.loadConfig(
        ClassPathResource("org/stellar/anchor/platform/configurator/def/config-defaults-v3.yaml"),
        DEFAULT
      )

    every { ConfigReader.getVersionSchemaFile(any()) } answers
      {
        String.format(
          "org/stellar/anchor/platform/configurator/def/config-def-v%d.yaml",
          firstArg<Int>()
        )
      }
  }

  @AfterEach
  fun teardown() {
    clearAllMocks()
    unmockkAll()
  }

  @Test
  @Order(1)
  fun `(scene-1) configuration with version upgrades`() {
    every { configManager.getConfigFileAsResource(any()) } answers
      {
        ClassPathResource("org/stellar/anchor/platform/configurator/scene-1/test.yaml")
      }

    val wantedConfig =
      ConfigHelper.loadConfig(
        ClassPathResource("org/stellar/anchor/platform/configurator/scene-1/wanted.yaml"),
        FILE
      )
    val gotConfig = configManager.processConfigurations(null)

    assertTrue(gotConfig.equals(wantedConfig))
  }

  @Test
  @Order(2)
  fun `(scene-2) bad configuration file`() {
    every { configManager.getConfigFileAsResource(any()) } answers
      {
        ClassPathResource("org/stellar/anchor/platform/configurator/scene-2/test.bad.yaml")
      }
    val ex = assertThrows<InvalidConfigException> { configManager.processConfigurations(null) }
    assertEquals(2, ex.messages.size)
    assertEquals("Invalid configuration: stellar.apollo=star. (version=1)", ex.messages[0])
    assertEquals("Invalid configuration: horizon.aster=star. (version=1)", ex.messages[1])
  }

  @Test
  @Order(3)
  fun `(scene-3) configuration from file and system environment variables with upgrades`() {
    every { configManager.getConfigFileAsResource(any()) } answers
      {
        ClassPathResource("org/stellar/anchor/platform/configurator/scene-3/test.yaml")
      }

    System.setProperty("stellar.bianca", "white")
    System.setProperty("stellar.deimos", "satellite")

    ConfigEnvironment.rebuild()

    val wantedConfig =
      ConfigHelper.loadConfig(
        ClassPathResource("org/stellar/anchor/platform/configurator/scene-3/wanted.yaml"),
        FILE
      )
    val gotConfig = configManager.processConfigurations(null)

    assertTrue(gotConfig.equals(wantedConfig))
  }

  @Test
  fun `test ConfigEnvironment getenv with line breaks and quotes`() {
    val multilineEnvName = "MULTILINE_ENV"
    val multilineEnvValue = """FOO=\"FOO\"\nBAR=\"BAR\""""
    val wantValue = "FOO=\"FOO\"\nBAR=\"BAR\""

    System.setProperty(multilineEnvName, multilineEnvValue)
    ConfigEnvironment.rebuild()

    assertEquals(wantValue, ConfigEnvironment.getenv(multilineEnvName))

    System.clearProperty(multilineEnvName)
    ConfigEnvironment.rebuild()
    assertNull(ConfigEnvironment.getenv(multilineEnvName))
  }

  @Test
  fun `test ConfigEnvironment getenv without line breaks or quotes`() {
    val simpleEnvName = "SIMPLE_ENV"
    val simpleEnvValue = "FOOBAR"
    val wantValue = "FOOBAR"

    System.setProperty(simpleEnvName, simpleEnvValue)
    ConfigEnvironment.rebuild()

    assertEquals(wantValue, ConfigEnvironment.getenv(simpleEnvName))

    System.clearProperty(simpleEnvName)
    ConfigEnvironment.rebuild()
    assertNull(ConfigEnvironment.getenv(simpleEnvName))
  }
}
