package org.stellar.reference.event

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.event.processor.AnchorEventProcessor

class EventConsumerTest {
  private val processor: AnchorEventProcessor = mockk(relaxed = true)

  private fun event(id: String) =
    SendEventRequest(id, "transaction_status_changed", "2024-01-01T00:00:00Z", null)

  @Test
  fun `consumer keeps running and handles the next event after handleEvent throws`() =
    runBlocking<Unit> {
      val channel = Channel<SendEventRequest>()
      val second = CompletableDeferred<Unit>()
      coEvery { processor.handleEvent(match { it.id == "bad" }) } throws
        IllegalStateException("boom")
      coEvery { processor.handleEvent(match { it.id == "good" }) } answers { second.complete(Unit) }

      val consumer = async(Dispatchers.Default) { EventConsumer(channel, processor).start() }
      channel.send(event("bad"))
      channel.send(event("good"))

      withTimeout(TimeUnit.SECONDS.toMillis(5)) { second.await() }
      assertFalse(consumer.isCompleted, "consumer must still be running")
      coVerify(exactly = 1) { processor.handleEvent(match { it.id == "good" }) }
      channel.close()
      withTimeout(TimeUnit.SECONDS.toMillis(5)) { consumer.await() }
    }

  @Test
  fun `consumer rethrows CancellationException from handleEvent`() =
    runBlocking<Unit> {
      val channel = Channel<SendEventRequest>()
      coEvery { processor.handleEvent(any()) } throws CancellationException("cancelled")

      val consumer = async(Dispatchers.Default) { EventConsumer(channel, processor).start() }
      channel.send(event("e1"))

      assertThrows<CancellationException> { withTimeout(5000) { consumer.await() } }
    }

  @Test
  fun `start returns when stop closes the channel`() =
    runBlocking<Unit> {
      val channel = Channel<SendEventRequest>()
      val eventConsumer = EventConsumer(channel, processor)
      val consumer = async(Dispatchers.Default) { eventConsumer.start() }

      eventConsumer.stop()

      withTimeout(5000) { consumer.await() }
      assertTrue(consumer.isCompleted)
    }
}
