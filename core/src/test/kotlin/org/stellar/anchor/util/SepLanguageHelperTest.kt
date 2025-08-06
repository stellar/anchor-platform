@file:Suppress("unused")

package org.stellar.anchor.util

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.config.LanguageConfig
import org.stellar.anchor.util.SepLanguageHelper.validateLanguage

class SepLanguageHelperTest {
  @MockK(relaxed = true) lateinit var languageConfig: LanguageConfig

  @BeforeEach
  fun setUp() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    SepLanguageHelper.reset()
  }

  @Test
  fun `test validateLanguage()`() {
    every { languageConfig.languages } returns
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
        "uk-UA",
      )

    Assertions.assertEquals("en", validateLanguage(languageConfig, "pt"))
    Assertions.assertEquals("en", validateLanguage(languageConfig, "uk"))
    Assertions.assertEquals("en", validateLanguage(languageConfig, "zh"))
    Assertions.assertEquals("en", validateLanguage(languageConfig, "en"))
    Assertions.assertEquals("es", validateLanguage(languageConfig, "es"))
    Assertions.assertEquals("fr", validateLanguage(languageConfig, "fr"))

    Assertions.assertEquals("fr-FR", validateLanguage(languageConfig, "fr-BE"))
    Assertions.assertEquals("fr-FR", validateLanguage(languageConfig, "fr-FR"))
    Assertions.assertEquals("fr-CA", validateLanguage(languageConfig, "fr-CA"))
    Assertions.assertEquals("zh-TW", validateLanguage(languageConfig, "zh-HK"))
    Assertions.assertEquals("es-ES", validateLanguage(languageConfig, "es-AR"))
    Assertions.assertEquals("es-ES", validateLanguage(languageConfig, "es-BR"))
    Assertions.assertEquals("uk-UA", validateLanguage(languageConfig, "uk-RU"))
    Assertions.assertEquals("en-US", validateLanguage(languageConfig, "pt-BR"))
    Assertions.assertEquals("en-US", validateLanguage(languageConfig, "en-UK"))
    Assertions.assertEquals("en-CA", validateLanguage(languageConfig, "en-CA"))
    Assertions.assertEquals("en-US", validateLanguage(languageConfig, "en-US"))

    Assertions.assertEquals("en-US", validateLanguage(languageConfig, null))
    Assertions.assertEquals("en-US", validateLanguage(languageConfig, "good-language"))
    Assertions.assertEquals("en", validateLanguage(languageConfig, "bad language"))
  }
}
