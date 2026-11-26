package org.stellar.anchor.platform.event

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.event.EventService
import org.stellar.anchor.event.EventService.EventQueue.TRANSACTION
import org.stellar.anchor.platform.config.PropertyEventConfig
import org.stellar.anchor.util.GsonUtils

class AfterCommitEventSessionTest {
  private val delegate = mockk<EventService.Session>(relaxed = true)
  private val session = AfterCommitEventSession(delegate)

  @Test
  fun `ack of part of a batch reaches the wrapped session`() {
    val response =
      object : EventService.ReadResponse {
        override fun getEvents(): List<AnchorEvent> = List(3) { mockk(relaxed = true) }
      }

    session.ack(response, 2)

    verify(exactly = 1) { delegate.ack(response, 2) }
  }

  @Test
  fun `keepAlive reaches the wrapped session`() {
    session.keepAlive()

    verify(exactly = 1) { delegate.keepAlive() }
  }

  @Test
  fun `a session created by DefaultEventService for Kafka forwards keepAlive to the Kafka session`() {
    val eventConfig =
      GsonUtils.getInstance()
        .fromJson(
          """
          {
            "enabled": true,
            "queue": {
              "type": "KAFKA",
              "kafka": {
                "bootstrapServer": "kafka:29092",
                "clientId": "testOk",
                "retries": 0,
                "lingerMs": 1000,
                "batchSize": 10,
                "pollTimeoutSeconds": 10
              }
            }
          }
          """,
          PropertyEventConfig::class.java,
        )!!
    mockkConstructor(KafkaSession::class)
    try {
      every { anyConstructed<KafkaSession>().keepAlive() } just Runs

      DefaultEventService(eventConfig).createSession("test", TRANSACTION).keepAlive()

      verify(exactly = 1) { anyConstructed<KafkaSession>().keepAlive() }
    } finally {
      unmockkConstructor(KafkaSession::class)
    }
  }
}
