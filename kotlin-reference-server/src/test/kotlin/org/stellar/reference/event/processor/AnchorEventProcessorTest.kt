package org.stellar.reference.event.processor

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.data.SendEventRequestPayload

class AnchorEventProcessorTest {
  private val sep6: Sep6EventProcessor = mockk(relaxed = true)
  private val sep31: Sep31EventProcessor = mockk(relaxed = true)
  private val processor = AnchorEventProcessor(sep6, sep31, NoOpEventProcessor())

  private fun event(type: String, payload: SendEventRequestPayload?) =
    SendEventRequest("evt-1", type, "2024-01-01T00:00:00Z", payload)

  @ParameterizedTest
  @ValueSource(strings = ["transaction_created", "transaction_status_changed", "quote_created"])
  fun `handleEvent with a null payload returns without calling a SEP processor`(type: String) {
    runBlocking { processor.handleEvent(event(type, null)) }

    coVerify(exactly = 0) { sep6.onTransactionCreated(any()) }
    coVerify(exactly = 0) { sep6.onTransactionStatusChanged(any()) }
    coVerify(exactly = 0) { sep6.onQuoteCreated(any()) }
    coVerify(exactly = 0) { sep31.onTransactionCreated(any()) }
    coVerify(exactly = 0) { sep31.onTransactionStatusChanged(any()) }
    coVerify(exactly = 0) { sep31.onQuoteCreated(any()) }
  }

  @Test
  fun `handleEvent with a null payload on customer_updated does not throw`() {
    runBlocking { processor.handleEvent(event("customer_updated", null)) }
  }

  @Test
  fun `handleEvent does not rethrow when the processor lookup fails`() {
    val broken: SendEventRequest = mockk()
    every { broken.payload } throws IllegalStateException("boom")
    every { broken.type } returns "transaction_created"
    every { broken.toString() } returns "broken-event"

    runBlocking { processor.handleEvent(broken) }
  }

  @Test
  fun `handleEvent dispatches SEP-6 and SEP-31 transactions to their processors`() {
    val sep6Tx = GetTransactionResponse().apply { sep = PlatformTransactionData.Sep.SEP_6 }
    val sep31Tx = GetTransactionResponse().apply { sep = PlatformTransactionData.Sep.SEP_31 }
    val sep6Event = event("transaction_created", SendEventRequestPayload(sep6Tx, null, null))
    val sep31Event = event("transaction_created", SendEventRequestPayload(sep31Tx, null, null))

    runBlocking {
      processor.handleEvent(sep6Event)
      processor.handleEvent(sep31Event)
    }

    coVerify(exactly = 1) { sep6.onTransactionCreated(sep6Event) }
    coVerify(exactly = 1) { sep31.onTransactionCreated(sep31Event) }
  }
}
