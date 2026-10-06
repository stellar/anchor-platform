package org.stellar.reference.plugins

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.shared.Customers
import org.stellar.anchor.api.shared.StellarId
import org.stellar.reference.data.Config
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.data.SendEventRequestPayload
import org.stellar.reference.event.processor.Sep6EventProcessor
import org.stellar.reference.service.SepHelper

class Sep6TestRouteTest {
  // The opt-out registry is process-wide, so every test uses a fresh id.
  private fun newId() = UUID.randomUUID().toString()

  private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      install(ContentNegotiation) { json() }
      routing { testSep6() }
    }
    block()
  }

  private val sepHelper: SepHelper = mockk(relaxed = true)
  private val processor =
    Sep6EventProcessor(
      mockk<Config>(relaxed = true),
      mockk(relaxed = true),
      mockk(relaxed = true),
      mockk(relaxed = true),
      sepHelper,
    )

  private fun statusChanged(id: String) =
    SendEventRequest(
      id = UUID.randomUUID().toString(),
      type = "transaction_status_changed",
      timestamp = "2026-10-01T00:00:00Z",
      payload =
        SendEventRequestPayload(
          transaction =
            GetTransactionResponse.builder()
              .id(id)
              .kind(Kind.DEPOSIT)
              .status(SepTransactionStatus.PENDING_USR_TRANSFER_START)
              .customers(
                Customers.builder()
                  .sender(StellarId.builder().account("GSENDER").build())
                  .receiver(StellarId.builder().account("GSENDER").build())
                  .build()
              )
              .build(),
          quote = null,
          customer = null,
        ),
    )

  @Test
  fun `registering a transaction answers 200 with its id and stops the processor reacting to it`() =
    routeTest {
      val id = newId()

      val response = client.post("/sep6/transactions/$id/skip-auto-advance")
      processor.onTransactionStatusChanged(statusChanged(id))

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("""{"sessionId":"$id"}""", response.bodyAsText())
      coVerify(exactly = 0) { sepHelper.rpcAction(any(), any()) }
    }

  @Test
  fun `registering the same transaction twice answers 200 both times`() = routeTest {
    val id = newId()

    val first = client.post("/sep6/transactions/$id/skip-auto-advance")
    val second = client.post("/sep6/transactions/$id/skip-auto-advance")

    assertEquals(HttpStatusCode.OK, first.status)
    assertEquals(HttpStatusCode.OK, second.status)
  }

  @Test
  fun `registering an id that matches no transaction answers 200`() = routeTest {
    val response = client.post("/sep6/transactions/does-not-exist-${newId()}/skip-auto-advance")

    assertEquals(HttpStatusCode.OK, response.status)
  }
}
