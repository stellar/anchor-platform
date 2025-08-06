@file:Suppress("unused")

package org.stellar.anchor.util

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.util.SepLanguageHelper.validateLanguage

class SepLanguageHelperTest {
  @MockK(relaxed = true) lateinit var stellarNetworkConfig: StellarNetworkConfig

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    SepLanguageHelper.reset()
  }

  @Test
  fun `test validateLanguage()`() {
    every { stellarNetworkConfig.languages } returns
      listOf(
        "en",
        "es",
        "fr",
        "en-US",
        "en-CA",
        "es-ES",
        "fr-FR",
        "fr-CA",
        "zh-TW",
        "zh-CN",
        "uk-UA"
      )

    Assertions.assertEquals("en", validateLanguage(stellarNetworkConfig, "pt"))
    Assertions.assertEquals("en", validateLanguage(stellarNetworkConfig, "uk"))
    Assertions.assertEquals("en", validateLanguage(stellarNetworkConfig, "zh"))
    Assertions.assertEquals("en", validateLanguage(stellarNetworkConfig, "en"))
    Assertions.assertEquals("es", validateLanguage(stellarNetworkConfig, "es"))
    Assertions.assertEquals("fr", validateLanguage(stellarNetworkConfig, "fr"))

    Assertions.assertEquals("fr-FR", validateLanguage(stellarNetworkConfig, "fr-BE"))
    Assertions.assertEquals("fr-FR", validateLanguage(stellarNetworkConfig, "fr-FR"))
    Assertions.assertEquals("fr-CA", validateLanguage(stellarNetworkConfig, "fr-CA"))
    Assertions.assertEquals("zh-TW", validateLanguage(stellarNetworkConfig, "zh-HK"))
    Assertions.assertEquals("es-ES", validateLanguage(stellarNetworkConfig, "es-AR"))
    Assertions.assertEquals("es-ES", validateLanguage(stellarNetworkConfig, "es-BR"))
    Assertions.assertEquals("uk-UA", validateLanguage(stellarNetworkConfig, "uk-RU"))
    Assertions.assertEquals("en-US", validateLanguage(stellarNetworkConfig, "pt-BR"))
    Assertions.assertEquals("en-US", validateLanguage(stellarNetworkConfig, "en-UK"))
    Assertions.assertEquals("en-CA", validateLanguage(stellarNetworkConfig, "en-CA"))
    Assertions.assertEquals("en-US", validateLanguage(stellarNetworkConfig, "en-US"))

    Assertions.assertEquals("en-US", validateLanguage(stellarNetworkConfig, null))
    Assertions.assertEquals("en-US", validateLanguage(stellarNetworkConfig, "good-language"))
    Assertions.assertEquals("en", validateLanguage(stellarNetworkConfig, "bad language"))
  }
}
