package org.stellar.anchor.platform.controller.sep

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.GsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.stellar.anchor.api.sep.sep45.ChallengeResponse
import org.stellar.anchor.api.sep.sep45.ValidationRequest
import org.stellar.anchor.api.sep.sep45.ValidationResponse
import org.stellar.anchor.config.SecretConfig
import org.stellar.anchor.config.StellarNetworkConfig
import org.stellar.anchor.sep45.Sep45Service
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network

/**
 * The transport of SEP-45 `/auth`: which keys a request is read from and a response is written
 * under. The servers use `GsonUtils.getInstance()` as the only JSON converter, so the controller is
 * set up standalone with the same converter (see `Sep1ControllerTest`); a body is sent as raw JSON,
 * never through the DTO, because a wrong key is symmetric when a client shares the DTO.
 */
class Sep45ControllerTest {
  private val spec = "spec-entries"

  private fun mvc(service: Sep45Service): MockMvc =
    MockMvcBuilders.standaloneSetup(Sep45Controller(service))
      .setMessageConverters(GsonHttpMessageConverter(GsonUtils.getInstance()))
      .build()

  // a service that runs the real validate(), which rejects an empty entries value first
  private fun realService(): Sep45Service {
    val secretConfig = mockk<SecretConfig>()
    val networkConfig = mockk<StellarNetworkConfig>()
    every { secretConfig.sep10SigningSeed } returns String(KeyPair.random().secretSeed)
    every { networkConfig.stellarNetworkPassphrase } returns Network.TESTNET.networkPassphrase
    return Sep45Service(
      networkConfig,
      secretConfig,
      mockk(),
      mockk(),
      mockk(),
      mockk(),
      mockk(relaxed = true),
    )
  }

  // WIRE-08
  @Test
  fun `test GET auth answers the challenge under the spec keys`() {
    val service = mockk<Sep45Service>()
    every { service.getChallenge(any()) } returns
      ChallengeResponse.builder()
        .authorizationEntries("entries")
        .networkPassphrase("passphrase")
        .build()

    mvc(service)
      .perform(get("/sep45/auth").param("account", "CA"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.authorization_entries").value("entries"))
      .andExpect(jsonPath("$.network_passphrase").value("passphrase"))
      .andExpect(jsonPath("$.authorizationEntries").doesNotExist())
      .andExpect(jsonPath("$.networkPassphrase").doesNotExist())
  }

  // WIRE-09
  @Test
  fun `test POST auth with a JSON body hands the spec key to the service`() {
    val service = mockk<Sep45Service>()
    val request = slot<ValidationRequest>()
    every { service.validate(capture(request)) } returns
      ValidationResponse.builder().token("jwt").build()

    mvc(service)
      .perform(
        post("/sep45/auth")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"authorization_entries": "$spec"}""")
      )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.token").value("jwt"))

    assertEquals(spec, request.captured.authorizationEntries)
  }

  // WIRE-09
  @Test
  fun `test POST auth with a form body hands the spec key to the service`() {
    val service = mockk<Sep45Service>()
    val request = slot<ValidationRequest>()
    every { service.validate(capture(request)) } returns
      ValidationResponse.builder().token("jwt").build()

    mvc(service)
      .perform(
        post("/sep45/auth")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .param("authorization_entries", spec)
      )
      .andExpect(status().isOk)

    assertEquals(spec, request.captured.authorizationEntries)
  }

  // WIRE-09
  @Test
  fun `test POST auth with a JSON body without authorization_entries answers 400`() {
    mvc(realService())
      .perform(post("/sep45/auth").contentType(MediaType.APPLICATION_JSON).content("{}"))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.error").value("authorization_entries is required"))
  }

  // a value that is not base64 is a client error, not a 500
  @Test
  fun `test POST auth with an authorization_entries value that is not base64 answers 400`() {
    for (value in listOf("spec-entries", "   ", "\\t", "!!!", "AA=A", "not base64!")) {
      mvc(realService())
        .perform(
          post("/sep45/auth")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"authorization_entries": "$value"}""")
        )
        .andExpect(status().isBadRequest)
        .andExpect(jsonPath("$.error").value("Failed to decode auth entries"))
    }
  }

  // WIRE-09: the camelCase name is not an accepted key
  @Test
  fun `test POST auth with only the camelCase key answers 400`() {
    mvc(realService())
      .perform(
        post("/sep45/auth")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"authorizationEntries": "$spec"}""")
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.error").value("authorization_entries is required"))
  }
}
