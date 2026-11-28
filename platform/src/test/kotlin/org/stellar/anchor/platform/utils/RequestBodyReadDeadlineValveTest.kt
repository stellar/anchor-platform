package org.stellar.anchor.platform.utils

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.DispatcherType
import org.apache.catalina.Valve
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.junit.jupiter.api.Test

class RequestBodyReadDeadlineValveTest {
  @Test
  fun `a re-dispatch of a request is passed on without touching its input buffer`() {
    for (dispatch in listOf(DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.FORWARD)) {
      val next = mockk<Valve>(relaxed = true)
      val valve = RequestBodyReadDeadlineValve(30).apply { setNext(next) }
      val request =
        mockk<Request>(relaxed = true) {
          every { contentLengthLong } returns 5000
          every { dispatcherType } returns dispatch
        }
      val response = mockk<Response>(relaxed = true)

      valve.invoke(request, response)

      verify(exactly = 1) { next.invoke(request, response) }
      verify(exactly = 0) { request.coyoteRequest }
    }
  }
}
