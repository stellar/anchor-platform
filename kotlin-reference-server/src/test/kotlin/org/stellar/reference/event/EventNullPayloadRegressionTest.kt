package org.stellar.reference.event

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT
import org.stellar.reference.event.processor.AnchorEventProcessor
import org.stellar.reference.event.processor.NoOpEventProcessor
import org.stellar.reference.event.processor.Sep31EventProcessor
import org.stellar.reference.event.processor.Sep6EventProcessor

// HackerOne #4091094: one unauthenticated POST /event without `payload` used to kill the only
// event consumer thread, so every later event hung on the rendezvous channel.
class EventNullPayloadRegressionTest {
  private val benign =
    """{"id":"ctl","type":"transaction_status_changed","timestamp":"2024-01-01T00:00:00Z",""" +
      """"payload":{"transaction":{"id":"t1"}}}"""

  private val poison =
    """{"id":"poc","type":"transaction_status_changed","timestamp":"2024-01-01T00:00:00Z"}"""

  @Test
  fun `a request without payload is rejected and later events are still processed`() {
    val service = EventService()
    val processor =
      AnchorEventProcessor(
        mockk<Sep6EventProcessor>(relaxed = true),
        mockk<Sep31EventProcessor>(relaxed = true),
        NoOpEventProcessor(),
      )
    val consumer = EventConsumer(service.channel, processor)
    val executor = Executors.newFixedThreadPool(1)
    val future: Future<*> = executor.submit { runBlocking { consumer.start() } }

    try {
      testApplication {
        application {
          // The shipped auth.type NONE wiring (ReferenceServerContainer).
          authentication { basic(AUTH_CONFIG_ENDPOINT) { skipWhen { true } } }
          routing { event(service, true) }
        }

        val crash =
          client.post("/event") {
            contentType(ContentType.Application.Json)
            setBody(poison)
          }
        assertEquals(HttpStatusCode.BadRequest, crash.status)
        assertFalse(future.isDone, "consumer must survive the payload-less request")

        val later =
          client.post("/event") {
            contentType(ContentType.Application.Json)
            setBody(benign)
          }
        assertEquals(HttpStatusCode.OK, later.status)
        assertEquals("""{"code":200,"message":"event processed"}""", later.bodyAsText())
      }

      // The accepted event reached the consumer through the channel.
      val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
      while (service.getLatestEvent() == null && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
      }
      assertEquals("ctl", service.getLatestEvent()?.id)
      assertFalse(future.isDone, "consumer must still be running")
    } finally {
      consumer.stop()
      executor.shutdownNow()
    }
  }

  @Test
  fun `an event with a null payload placed directly on the channel does not kill the consumer`() {
    val service = EventService()
    val sep6 = mockk<Sep6EventProcessor>(relaxed = true)
    val processor = AnchorEventProcessor(sep6, mockk(relaxed = true), NoOpEventProcessor())
    val consumer = EventConsumer(service.channel, processor)
    val executor = Executors.newFixedThreadPool(1)
    val future: Future<*> = executor.submit { runBlocking { consumer.start() } }

    try {
      runBlocking {
        service.channel.send(
          org.stellar.reference.data.SendEventRequest(
            "p",
            "transaction_status_changed",
            "2024-01-01T00:00:00Z",
            null,
          )
        )
        // Rendezvous: this send completes only if the consumer is alive and receiving.
        service.channel.send(
          org.stellar.reference.data.SendEventRequest(
            "q",
            "transaction_status_changed",
            "2024-01-01T00:00:00Z",
            null,
          )
        )
      }
      assertFalse(future.isDone, "consumer must still be running")
      coVerify(exactly = 0) { sep6.onTransactionStatusChanged(any()) }
    } finally {
      consumer.stop()
      executor.shutdownNow()
    }
  }
}
