package org.stellar.anchor.platform.controller.sep

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.GsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.stellar.anchor.config.Sep1Config.TomlType.STRING
import org.stellar.anchor.platform.config.PropertySep1Config
import org.stellar.anchor.platform.config.PropertySep1Config.TomlConfig
import org.stellar.anchor.sep1.Sep1Service
import org.stellar.anchor.util.GsonUtils

/**
 * The transport of `/.well-known/stellar.toml`: which `Accept` values are served, the content type,
 * CORS, the landing page redirect and the disabled answer. The whole app cannot be slice-tested
 * (see `AbstractControllerExceptionHandlerTest`), so the controller is set up standalone; the e2e
 * `Sep1Tests` asserts the same over a live stack.
 */
class Sep1ControllerTest {
  private val toml =
    """
    VERSION = "0.1.0"
    NETWORK_PASSPHRASE = "Test SDF Network ; September 2015"
    """
      .trimIndent()

  private fun mvc(enabled: Boolean = true): MockMvc {
    val config = PropertySep1Config(enabled, TomlConfig(STRING, toml))
    return MockMvcBuilders.standaloneSetup(Sep1Controller(config, Sep1Service(config)))
      .setMessageConverters(
        StringHttpMessageConverter(Charsets.UTF_8),
        GsonHttpMessageConverter(GsonUtils.getInstance()),
      )
      .build()
  }

  // SEP1SV-01, SEP1SV-02, SEP1SV-03, SEP1SV-04
  @ParameterizedTest
  @ValueSource(strings = ["*/*", "text/plain", "application/toml", "application/json"])
  fun `test the TOML is served as text plain for every accepted media type`(accept: String) {
    val result =
      mvc()
        .perform(get("/.well-known/stellar.toml").header("Accept", accept))
        .andExpect(status().isOk)
        .andExpect(content().string(toml))
        .andReturn()

    assertTrue(result.response.contentType!!.startsWith("text/plain")) {
      "Content-Type was ${result.response.contentType}"
    }
  }

  // SEP1SV edge case: not a TOML media type
  @Test
  fun `test the TOML is not acceptable as text html`() {
    mvc()
      .perform(get("/.well-known/stellar.toml").header("Accept", MediaType.TEXT_HTML_VALUE))
      .andExpect(status().isNotAcceptable)
  }

  // SEP1SV-07
  @Test
  fun `test GET answers with Access-Control-Allow-Origin star`() {
    mvc()
      .perform(get("/.well-known/stellar.toml").header("Origin", "https://wallet.example"))
      .andExpect(status().isOk)
      .andExpect(header().string("Access-Control-Allow-Origin", "*"))
  }

  // SEP1SV-10
  @Test
  fun `test the landing page redirects to the TOML`() {
    mvc()
      .perform(get("/"))
      .andExpect(status().isFound)
      .andExpect(redirectedUrl("/.well-known/stellar.toml"))
  }

  // SEP1SV-11
  @Test
  fun `test a disabled SEP-1 answers 404 for the TOML and the landing page`() {
    for (path in listOf("/.well-known/stellar.toml", "/")) {
      mvc(enabled = false)
        .perform(get(path))
        .andExpect(status().isNotFound)
        .andExpect(content().json("""{"error":"Not Found"}""", true))
    }
  }
}
