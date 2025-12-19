package org.stellar.anchor.platform.configurator

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.spyk
import kotlin.test.assertNull
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.ClassPathResource
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.platform.configurator.ConfigMap.ConfigSource.DEFAULT
import org.stellar.anchor.platform.configurator.ConfigMap.ConfigSource.FILE

@ExtendWith(LockAndMockTest::class)
class ConfigManagerTest {
  private val configManager =
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
    assertEquals(config.get("clients.items[0].name").value, "vibrant")
    assertEquals(config.get("clients.items[0].domains[0]").value, "vibrant.co")
    assertEquals(
      config.get("clients.items[0].callback_urls.sep24").value,
      "https://callback.vibrant.com/api/v2/anchor/callback/sep24"
    )

    assertEquals(config.get("clients.items[1].name").value, "lobstr")
    assertEquals(config.get("clients.items[1].domains[0]").value, "lobstr.co")
    assertEquals(
      config.get("clients.items[1].callback_urls.sep6").value,
      "https://callback.lobstr.co/api/v2/anchor/callback/sep6"
    )
  }

  @Test
  fun `test reading a file missing version throws an exception`() {
    val testingConfigFile = ClassPathResource("config/test_anchor_config_missing_version.yaml")
    every { configManager.getConfigFileAsResource(any()) } returns testingConfigFile

    val ex = assertThrows<IllegalStateException> { configManager.processConfigurations(null) }
    assertEquals(
      ex.message,
      "java.io.FileNotFoundException: class path resource [config/anchor-config-schema-v0.yaml] cannot be opened because it does not exist"
    )
  }
}

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(LockAndMockTest::class)
class ConfigManagerTestExt {
  private lateinit var configManager: ConfigManager

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
  }

  @LockAndMockStatic([ConfigReader::class, ConfigHelper::class])
  fun `add default config mocks`() {
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

  @Test
  @Order(1)
  @LockAndMockStatic([ConfigReader::class, ConfigHelper::class])
  fun `(scene-1) configuration with version upgrades`() {
    `add default config mocks`()
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
  @LockAndMockStatic([ConfigReader::class, ConfigHelper::class])
  fun `(scene-2) bad configuration file`() {
    `add default config mocks`()
    every { configManager.getConfigFileAsResource(any()) } answers
      {
        ClassPathResource("org/stellar/anchor/platform/configurator/scene-2/test.bad.yaml")
      }
    val ex = assertThrows<InvalidConfigException> { configManager.processConfigurations(null) }
    assertEquals(2, ex.messages.size)
    assertEquals("Invalid configuration: horizon.aster=star. (version=1)", ex.messages[0])
    assertEquals("Invalid configuration: stellar.apollo=star. (version=1)", ex.messages[1])
  }

  @Test
  @Order(3)
  @LockAndMockStatic([ConfigReader::class, ConfigHelper::class])
  fun `(scene-3) configuration from file and system environment variables with upgrades`() {
    `add default config mocks`()
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
  @LockAndMockStatic([ConfigReader::class, ConfigHelper::class])
  fun `test ConfigEnvironment getenv with line breaks and quotes`() {
    `add default config mocks`()
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
  @LockAndMockStatic([ConfigReader::class, ConfigHelper::class])
  fun `test ConfigEnvironment getenv without line breaks or quotes`() {
    `add default config mocks`()
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
