package org.stellar.reference.event

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.data.SendEventRequestPayload

class EventServiceTest {
  private val cap = 10_000

  private fun event(
    id: String,
    type: String = "unsupported_type",
    txnId: String? = null
  ): SendEventRequest =
    SendEventRequest(
      id = id,
      type = type,
      timestamp = Instant.now().toString(),
      payload =
        SendEventRequestPayload(
          transaction = txnId?.let { GetTransactionResponse.builder().id(it).build() },
          quote = null,
          customer = null,
        )
    )

  // Drains the rendezvous channel the way EventConsumer does and records what it delivered.
  private fun withService(
    retainEvents: Boolean,
    block: suspend (EventService, AtomicInteger) -> Unit
  ) = runBlocking {
    val service = EventService(retainEvents)
    val delivered = AtomicInteger()
    val drain =
      launch(Dispatchers.Default) { service.channel.consumeEach { delivered.incrementAndGet() } }
    try {
      block(service, delivered)
    } finally {
      drain.cancel()
    }
  }

  @Test
  fun `retains nothing and still delivers every event when retention is off`() =
    withService(retainEvents = false) { service, delivered ->
      repeat(cap + 5) { service.processEvent(event("e$it")) }

      assertEquals(0, service.getEvents(null).size)
      assertNull(service.getLatestEvent())
      // the rendezvous send returns once the drain received the event; the counter follows
      while (delivered.get() < cap + 5) Thread.sleep(1)
      assertEquals(cap + 5, delivered.get())
    }

  @Test
  fun `keeps the newest 10000 events after 15000 are accepted`() =
    withService(retainEvents = true) { service, _ ->
      repeat(15_000) { service.processEvent(event("e$it")) }

      val events = service.getEvents(null)
      assertEquals(cap, events.size)
      assertEquals("e5000", events.first().id)
      assertEquals("e14999", events.last().id)
    }

  @Test
  fun `evicts nothing when exactly 10000 events are accepted`() =
    withService(retainEvents = true) { service, _ ->
      repeat(cap) { service.processEvent(event("e$it")) }

      val events = service.getEvents(null)
      assertEquals(cap, events.size)
      assertEquals("e0", events.first().id)
      assertEquals("e9999", events.last().id)
    }

  @Test
  fun `returns retained events in arrival order below the cap`() =
    withService(retainEvents = true) { service, _ ->
      listOf("a", "b", "c").forEach { service.processEvent(event(it)) }

      assertEquals(listOf("a", "b", "c"), service.getEvents(null).map { it.id })
    }

  @Test
  fun `filters by transaction id and drops quote created events`() =
    withService(retainEvents = true) { service, _ ->
      service.processEvent(event("a", txnId = "t1"))
      service.processEvent(event("b", txnId = "t2"))
      service.processEvent(event("c", type = AnchorEvent.Type.QUOTE_CREATED.type, txnId = "t1"))
      service.processEvent(event("d", txnId = "t1"))

      assertEquals(listOf("a", "d"), service.getEvents("t1").map { it.id })
    }

  @Test
  fun `latest event is the newest accepted one after eviction`() =
    withService(retainEvents = true) { service, _ ->
      repeat(cap + 1) { service.processEvent(event("e$it")) }

      assertEquals("e$cap", service.getLatestEvent()?.id)
    }

  @Test
  fun `clearEvents leaves zero retained events`() =
    withService(retainEvents = true) { service, _ ->
      repeat(3) { service.processEvent(event("e$it")) }

      service.clearEvents()

      assertEquals(0, service.getEvents(null).size)
      assertNull(service.getLatestEvent())
    }

  @Test
  fun `retains exactly 10000 events when 64 coroutines accept 20000 concurrently`() =
    withService(retainEvents = true) { service, _ ->
      coroutineScope {
        (0 until 64)
          .map { w ->
            async(Dispatchers.Default) {
              for (i in w until 20_000 step 64) service.processEvent(event("e$i"))
            }
          }
          .awaitAll()
      }

      assertEquals(cap, service.getEvents(null).size)
    }

  @Test
  fun `getEvents returns a snapshot that later events do not change`() =
    withService(retainEvents = true) { service, _ ->
      service.processEvent(event("a"))
      val snapshot = service.getEvents(null)

      service.processEvent(event("b"))

      assertEquals(listOf("a"), snapshot.map { it.id })
    }
}
