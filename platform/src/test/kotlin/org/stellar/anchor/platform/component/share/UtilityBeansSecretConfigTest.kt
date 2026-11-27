package org.stellar.anchor.platform.component.share

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.stellar.anchor.platform.config.PropertySecretConfig
import org.stellar.anchor.platform.configurator.SecretManager
import org.stellar.anchor.platform.utils.PublishedLiterals
import org.stellar.anchor.platform.utils.RecordingLogger

/**
 * ANCHOR-1546: the secret checks must run through the real `UtilityBeans` declaration. A test that
 * registers `PropertySecretConfig` by hand would pass with the wiring missing, so the
 * `secretConfig` bean here is created by `UtilityBeans` itself.
 */
class UtilityBeansSecretConfigTest {
  private lateinit var secretManager: SecretManager

  /** What the real singleton holds for the SEP-10 secret before this test mocks it. */
  private var realSep10Secret: String? = null

  private val freshSecrets =
    PublishedLiterals.secretsByProperty.keys.mapIndexed { i, property ->
      property to "fresh_secret_${i}_" + "0123456789abcdef".repeat(3)
    }

  @BeforeEach
  fun setup() {
    realSep10Secret = SecretManager.getInstance().get(PropertySecretConfig.SECRET_SEP_10_JWT_SECRET)
    secretManager = mockk()
    mockkStatic(SecretManager::class)
    every { secretManager.get(any()) } returns null
    every { SecretManager.getInstance() } returns secretManager
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  private fun configure(vararg secrets: Pair<String, String>) {
    every { secretManager.get(any()) } returns null
    secrets.forEach { (property, value) -> every { secretManager.get(property) } returns value }
  }

  /**
   * `UtilityBeans` declares beans that need the asset, client and ledger services. Only
   * `secretConfig` is created eagerly, so the test needs none of them.
   */
  private fun runner() =
    ApplicationContextRunner()
      .withUserConfiguration(UtilityBeans::class.java)
      .withBean(
        "onlySecretConfigIsEager",
        BeanFactoryPostProcessor::class.java,
        Supplier {
          BeanFactoryPostProcessor { beanFactory ->
            beanFactory.beanDefinitionNames
              .filter { it != "secretConfig" }
              .forEach { beanFactory.getBeanDefinition(it).isLazyInit = true }
          }
        },
      )

  private fun fullMessage(context: AssertableApplicationContext): String =
    generateSequence(context.startupFailure as Throwable?) { it.cause }
      .joinToString("\n") { it.message ?: "" }

  @Test
  fun `test the context fails to start on a published sep10 secret and does not print it`() {
    val property = PropertySecretConfig.SECRET_SEP_10_JWT_SECRET
    val published = PublishedLiterals.secretsByProperty.getValue(property)
    configure(*freshSecrets.toTypedArray(), property to published)
    val log = RecordingLogger().install()

    runner().run { context ->
      assertNotNull(context.startupFailure)
      val message = fullMessage(context)
      assertTrue(message.contains("secret-published"), message)
      assertTrue(message.contains(property), message)
      assertFalse(message.contains(published), "the failure repeats the secret")
      assertFalse(log.lines.any { it.contains(published) }, "a log line repeats the secret")
    }
  }

  @Test
  fun `test the context starts with seven distinct strong secrets`() {
    configure(*freshSecrets.toTypedArray())

    runner().run { context ->
      assertNull(context.startupFailure)
      assertNotNull(context.getBean(PropertySecretConfig::class.java))
    }
  }

  @Test
  fun `test the context starts with a published signing seed`() {
    configure(
      *freshSecrets.toTypedArray(),
      PropertySecretConfig.SECRET_SEP_10_SIGNING_SEED to PublishedLiterals.signingSeeds[0],
    )

    runner().run { context -> assertNull(context.startupFailure) }
  }

  @Test
  fun `test the real secret manager holds the same value after a failed run`() {
    val property = PropertySecretConfig.SECRET_SEP_10_JWT_SECRET
    configure(property to PublishedLiterals.secretsByProperty.getValue(property))
    runner().run { context -> assertNotNull(context.startupFailure) }

    unmockkAll()

    assertEquals(realSep10Secret, SecretManager.getInstance().get(property))
  }
}
