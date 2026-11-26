package org.stellar.anchor.platform.guard

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.validation.BindException
import org.stellar.anchor.platform.config.PropertySecretConfig
import org.stellar.anchor.platform.configurator.SecretManager

/**
 * ANCHOR-1546: the startup checks now run, so every secret file this repository ships must pass
 * them. A shipped profile with an equal, weak or published secret would stop the application it is
 * meant to start.
 */
class ShippedSecretsValidTest {
  private val repoRoot = File("..")

  /** The secret properties the startup checks read, keyed by their `SECRET_*` environment name. */
  private val secretProperties =
    listOf(
      PropertySecretConfig.SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET,
      PropertySecretConfig.SECRET_SEP_10_JWT_SECRET,
      PropertySecretConfig.SECRET_SEP_10_SIGNING_SEED,
      PropertySecretConfig.SECRET_SEP_24_INTERACTIVE_URL_JWT_SECRET,
      PropertySecretConfig.SECRET_SEP_24_MORE_INFO_URL_JWT_SECRET,
      PropertySecretConfig.SECRET_SEP_45_JWT_SECRET,
      PropertySecretConfig.SECRET_CALLBACK_API_AUTH_SECRET,
      PropertySecretConfig.SECRET_PLATFORM_API_AUTH_SECRET,
    )

  private lateinit var secretManager: SecretManager

  @BeforeEach
  fun setup() {
    secretManager = mockk()
    mockkStatic(SecretManager::class)
    every { secretManager.get(any()) } returns null
    every { SecretManager.getInstance() } returns secretManager
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /** `name=value` lines of a shipped file; blank lines and `#` comments are skipped. */
  private fun keyValues(text: String): Map<String, String> =
    text
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
      .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim().trim('"') }

  /** The secrets a file defines, by property name, whether it uses `secret.x.y` or `SECRET_X_Y`. */
  private fun secretsOf(text: String): Map<String, String> {
    val values = keyValues(text)
    return secretProperties
      .mapNotNull { property ->
        val value = values[property] ?: values[property.uppercase().replace('.', '_')]
        if (value.isNullOrEmpty()) null else property to value
      }
      .toMap()
  }

  private fun validate(secrets: Map<String, String>): BindException {
    every { secretManager.get(any()) } returns null
    secrets.forEach { (property, value) -> every { secretManager.get(property) } returns value }
    val config = PropertySecretConfig()
    val errors = BindException(config, "config")
    config.validate(config, errors)
    return errors
  }

  private fun shipped(path: String): Map<String, String> =
    secretsOf(File(repoRoot, path).readText())

  @Test
  fun `test the default profile passes the startup checks`() {
    val secrets = shipped("service-runner/src/main/resources/profiles/default/config.env")

    assertTrue(secrets.size >= 7, "the default profile defines its JWT and API secrets: $secrets")
    val errors = validate(secrets)
    assertFalse(errors.hasErrors(), errors.allErrors.joinToString { it.defaultMessage.orEmpty() })
  }

  @Test
  fun `test quick-run dev env passes the startup checks`() {
    val secrets = shipped("quick-run/dev.env")

    assertTrue(secrets.isNotEmpty(), "quick-run/dev.env defines its JWT secrets")
    val errors = validate(secrets)
    assertFalse(errors.hasErrors(), errors.allErrors.joinToString { it.defaultMessage.orEmpty() })
  }

  @Test
  fun `test env example passes the startup checks`() {
    val errors = validate(shipped(".env.example"))

    assertFalse(errors.hasErrors(), errors.allErrors.joinToString { it.defaultMessage.orEmpty() })
  }

  @Test
  fun `test the helper reports an equal secret in a profile`() {
    val profile = File(repoRoot, "service-runner/src/main/resources/profiles/default/config.env")
    val secrets = secretsOf(profile.readText()).toMutableMap()
    secrets[PropertySecretConfig.SECRET_SEP_10_JWT_SECRET] =
      secrets.getValue(PropertySecretConfig.SECRET_SEP_6_MORE_INFO_URL_JWT_SECRET)

    val errors = validate(secrets)

    assertEquals(1, errors.errorCount)
    assertEquals("secrets.jwt.must_be_unique", errors.allErrors[0].code)
  }
}
