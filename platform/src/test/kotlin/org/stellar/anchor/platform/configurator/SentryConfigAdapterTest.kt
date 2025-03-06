package org.stellar.anchor.platform.configurator

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.InvalidConfigException

class SentryConfigAdapterTest {
  @Test
  fun `test valid config`() {
    val sentryConfigAdapter = spyk(SentryConfigAdapter())
    val config = populateValidConfig()

    every { sentryConfigAdapter.initSentry(any(), any(), any()) } answers {}
    every { sentryConfigAdapter.authToken } returns "authToken"

    sentryConfigAdapter.updateSpringEnv(config)
  }

  @Test
  fun `test missing SENTRY_AUTH_TOKEN`() {
    val sentryConfigAdapter = spyk(SentryConfigAdapter())
    val config = populateValidConfig()
    every { sentryConfigAdapter.authToken } returns ""

    assertThrows<InvalidConfigException> { sentryConfigAdapter.validate(config) }
  }

  @Test
  fun `test missing sentry release`() {
    val sentryConfigAdapter = spyk(SentryConfigAdapter())
    val config = populateValidConfig()
    every { config.getString("sentry.release") } returns ""

    assertThrows<InvalidConfigException> { sentryConfigAdapter.validate(config) }
  }

  @Test
  fun `test missing sentry environment`() {
    val sentryConfigAdapter = spyk(SentryConfigAdapter())
    val config = populateValidConfig()
    every { config.getString("sentry.environment") } returns ""

    assertThrows<InvalidConfigException> { sentryConfigAdapter.validate(config) }
  }

  private fun populateValidConfig(): ConfigMap {
    val config: ConfigMap = mockk()
    every { config.getString("sentry.dsn") } returns
      "https://a697cb---203.ingest.us.sentry.io/4508---896"
    every { config.getString("sentry.environment") } returns "dev"
    every { config.getString("sentry.release") } returns "release-1.0"
    every { config.getBoolean("sentry.debug") } returns true
    return config
  }
}
