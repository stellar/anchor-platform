package org.stellar.anchor.platform.controller

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.GsonHttpMessageConverter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.stellar.anchor.platform.controller.platform.PlatformControllerExceptionHandler
import org.stellar.anchor.platform.controller.sep.SepControllerExceptionHandler
import org.stellar.anchor.platform.utils.RequestBodyReadDeadlineValve.RequestBodyReadTimeoutException
import org.stellar.anchor.util.GsonUtils

class RequestBodyReadTimeoutHandlerTest {
  @RestController
  class EchoController {
    @PostMapping("/echo", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun echo(@RequestBody body: Map<String, Any>): Map<String, Any> = body
  }

  /**
   * A request whose body stream fails the way the valve's wrapper does once the deadline passed.
   */
  private class TimedOutRequest(context: ServletContext?) : MockHttpServletRequest(context) {
    override fun getInputStream(): ServletInputStream =
      object : ServletInputStream() {
        override fun read(): Int = throw RequestBodyReadTimeoutException(30_000)

        override fun isFinished(): Boolean = false

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener?) {}
      }
  }

  private fun post() =
    MockMvcRequestBuilders.post("/echo").with { request ->
      TimedOutRequest(request.servletContext).apply {
        method = "POST"
        requestURI = "/echo"
        contentType = MediaType.APPLICATION_JSON_VALUE
      }
    }

  private fun assert408(advice: Any) {
    MockMvcBuilders.standaloneSetup(EchoController())
      .setControllerAdvice(advice)
      .setMessageConverters(GsonHttpMessageConverter(GsonUtils.getInstance()))
      .build()
      .perform(post())
      .andExpect(status().isRequestTimeout)
      .andExpect(jsonPath("$.error").value("The request body took too long to arrive."))
  }

  @Test
  fun `a SEP controller answers 408 when the JSON body times out`() {
    assert408(SepControllerExceptionHandler())
  }

  @Test
  fun `a Platform controller answers 408 when the JSON body times out`() {
    assert408(PlatformControllerExceptionHandler())
  }

  @Test
  fun `a JSON body that fails for another reason is still a 400`() {
    val response =
      MockMvcBuilders.standaloneSetup(EchoController())
        .setControllerAdvice(SepControllerExceptionHandler())
        .setMessageConverters(GsonHttpMessageConverter(GsonUtils.getInstance()))
        .build()
        .perform(
          MockMvcRequestBuilders.post("/echo")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not json")
        )
        .andReturn()
        .response

    assertEquals(400, response.status)
  }
}
