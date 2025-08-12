package org.stellar.anchor.platform.config

import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors

class LanguageConfigTest {
  private lateinit var config: PropertyLanguageConfig
  private lateinit var errors: Errors

  @BeforeEach
  fun setUp() {
    config = PropertyLanguageConfig()
    errors = BindException(config, "config")
  }

  @ParameterizedTest
  @NullSource
  @MethodSource("validLanguages")
  fun `test valid languages`(langs: List<String>?) {
    config.languages = langs
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @MethodSource("invalidLanguages")
  fun `test invalid languages`(langs: List<String>) {
    config.languages = langs
    config.validate(config, errors)
    assertErrorCode(errors, "languages-invalid")
  }

  companion object {
    @JvmStatic
    fun validLanguages(): Stream<List<String>> {
      return Stream.of(listOf(), listOf("en", "en-us", "EN", "EN-US"), listOf("zh-tw", "zh"))
    }

    @JvmStatic
    fun invalidLanguages(): Stream<List<String>> {
      return Stream.of(listOf("1234", "EN", "EN-US"))
    }
  }
}
