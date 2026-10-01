package org.stellar.reference.event

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.event.processor.AnchorEventProcessor
import org.stellar.reference.log

@OptIn(ExperimentalCoroutinesApi::class)
class EventConsumer(
  private val channel: Channel<SendEventRequest>,
  private val processor: AnchorEventProcessor,
) {
  suspend fun start(): EventConsumer {
    while (true) {
      // receiveCatching() suspends until an event is available or the channel is closed,
      // instead of busy-polling channel.isEmpty in a tight loop and pinning a whole CPU core.
      val event = channel.receiveCatching().getOrNull() ?: break
      log.info { "Processing event ${event.id} of type ${event.type}" }
      processor.handleEvent(event)
    }
    return this
  }

  // Closing the channel (rather than flipping a flag) wakes up a coroutine suspended in
  // receiveCatching() immediately, so shutdown doesn't wait for the next event to arrive.
  fun stop() {
    channel.close()
  }
}
