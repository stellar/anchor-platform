package org.stellar.anchor.platform.controller.sep

import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.GsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.stellar.anchor.config.Sep1Config.TomlType.STRING
import org.stellar.anchor.platform.config.PropertySep1Config
import org.stellar.anchor.platform.config.PropertySep1Config.TomlConfig
import org.stellar.anchor.platform.service.HealthCheckService
import org.stellar.anchor.platform.utils.OriginHeaderFilter
import org.stellar.anchor.sep1.Sep1Service
import org.stellar.anchor.sep10.Sep10Service
import org.stellar.anchor.util.GsonUtils

/**
 * HackerOne #4240773: the routes the reporter ran (SEP-1 TOML, `/health`, SEP-10 `GET /auth`) must
 * refuse the attack `Origin` before Spring's CORS processing parses it and before the controller
 * runs. The real controllers sit behind the real filter in a standalone MockMvc, so the
 * `@CrossOrigin` processing is the one the servers run. The header is the report's shape at 404
 * characters, which costs about 150 ms without the filter (the full 8 KB would take minutes).
 */
class OriginHeaderRoutesTest {
  private val toml =
    """
    VERSION = "0.1.0"
    NETWORK_PASSPHRASE = "Test SDF Network ; September 2015"
    """
      .trimIndent()

  private val healthCheckService = mockk<HealthCheckService>(relaxed = true)
  private val sep10Service = mockk<Sep10Service>(relaxed = true)
  private val sep1Config = PropertySep1Config(true, TomlConfig(STRING, toml))
  private val sep1Service = spyk(Sep1Service(sep1Config))

  private val attackOrigin = "//" + "@".repeat(400) + "#" + '\u0085'

  private fun mvc(): MockMvc {
    return MockMvcBuilders.standaloneSetup(
        Sep1Controller(sep1Config, sep1Service),
        SepHealthController(healthCheckService),
        Sep10Controller(sep10Service),
      )
      .addFilters<StandaloneMockMvcBuilder>(OriginHeaderFilter())
      .setMessageConverters(
        StringHttpMessageConverter(Charsets.UTF_8),
        GsonHttpMessageConverter(GsonUtils.getInstance()),
      )
      .build()
  }

  // ORIGIN-06
  @Test
  fun `the SEP-1 TOML route refuses the attack origin and never reaches the controller`() {
    mvc()
      .perform(get("/.well-known/stellar.toml").header("Origin", attackOrigin))
      .andExpect(status().isBadRequest)
      .andExpect(content().string("""{"error":"Invalid Origin header."}"""))

    verify(exactly = 0) { sep1Service.getToml() }
  }

  // ORIGIN-06 (a preflight runs the same CORS check)
  @Test
  fun `the SEP-1 TOML preflight refuses the attack origin`() {
    mvc()
      .perform(
        options("/.well-known/stellar.toml")
          .header("Origin", attackOrigin)
          .header("Access-Control-Request-Method", "GET")
      )
      .andExpect(status().isBadRequest)
  }

  // ORIGIN-07
  @Test
  fun `the health route refuses the attack origin and never reaches the controller`() {
    mvc()
      .perform(get("/health").header("Origin", attackOrigin))
      .andExpect(status().isBadRequest)
      .andExpect(content().string("""{"error":"Invalid Origin header."}"""))

    verify(exactly = 0) { healthCheckService.check(any()) }
  }

  // ORIGIN-08
  @Test
  fun `the SEP-10 challenge route refuses the attack origin and never reaches the controller`() {
    mvc()
      .perform(
        get("/auth")
          .param("account", "GBDYDBJKQBJK4GY4V7FAONSFF2ISA6RSEYEHPTYYLVNEZTLXZ5O37YLL")
          .header("Origin", attackOrigin)
      )
      .andExpect(status().isBadRequest)
      .andExpect(content().string("""{"error":"Invalid Origin header."}"""))

    verify(exactly = 0) { sep10Service.createChallenge(any(), any()) }
  }

  // ORIGIN-14
  @Test
  fun `a wallet origin still gets the TOML with Access-Control-Allow-Origin star`() {
    val result =
      mvc()
        .perform(get("/.well-known/stellar.toml").header("Origin", "https://wallet.example"))
        .andExpect(status().isOk)
        .andExpect(header().string("Access-Control-Allow-Origin", "*"))
        .andReturn()

    assertEquals(toml, result.response.contentAsString)
  }
}
