package org.stellar.anchor.platform.controller.sep

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.http.converter.json.GsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.stellar.anchor.api.exception.InvalidConfigException
import org.stellar.anchor.api.sep.sep10.ValidationResponse
import org.stellar.anchor.platform.component.sep.SepBeans
import org.stellar.anchor.platform.config.SepServerConfig
import org.stellar.anchor.platform.utils.RequestBodySizeLimitFilter
import org.stellar.anchor.sep10.Sep10Service
import org.stellar.anchor.util.GsonUtils

class SepRequestLimitsTest {
  private lateinit var sep10Service: Sep10Service

  @BeforeEach
  fun setup() {
    sep10Service = mockk()
    every { sep10Service.validateChallenge(any()) } returns ValidationResponse.of("token")
  }

  private fun mvc(maxBodySize: Long): MockMvc =
    MockMvcBuilders.standaloneSetup(Sep10Controller(sep10Service))
      .addFilters<StandaloneMockMvcBuilder>(RequestBodySizeLimitFilter(maxBodySize))
      .setMessageConverters(GsonHttpMessageConverter(GsonUtils.getInstance()))
      .build()

  private fun authBody(transaction: String): String =
    GsonUtils.getInstance().toJson(mapOf("transaction" to transaction))

  @Test
  fun `an auth body over the size limit is rejected with 413 before it reaches the controller`() {
    mvc(maxBodySize = 1024)
      .perform(
        post("/auth").contentType(MediaType.APPLICATION_JSON).content(authBody("A".repeat(4096)))
      )
      .andExpect(status().isPayloadTooLarge)

    verify(exactly = 0) { sep10Service.validateChallenge(any()) }
  }

  @Test
  fun `an auth body within the size limit reaches the controller`() {
    mvc(maxBodySize = 1024 * 1024)
      .perform(post("/auth").contentType(MediaType.APPLICATION_JSON).content(authBody("AAAA")))
      .andExpect(status().isOk)

    verify(exactly = 1) { sep10Service.validateChallenge(any()) }
  }

  @Test
  fun `the sep filter is registered on every path with the configured size`() {
    val bean =
      SepBeans()
        .sepRequestBodySizeLimitFilter(SepServerConfig().apply { maxRequestBodySize = 2048 })

    assertEquals(listOf("/*"), bean.urlPatterns.toList())
    assertEquals(Ordered.HIGHEST_PRECEDENCE, bean.order)
  }

  @Test
  fun `a non-positive sep max body size fails startup`() {
    assertThrows<InvalidConfigException> {
      SepBeans().sepRequestBodySizeLimitFilter(SepServerConfig().apply { maxRequestBodySize = 0 })
    }
  }
}
