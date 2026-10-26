package org.stellar.reference.event.processor

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.data.SendEventRequestPayload

class Sep31EventProcessorOptOutTest {
  private val transactionId = "2a337bba-4280-41ee-8632-2f2752ee2a42"

  @BeforeEach fun resetOptOuts() = Sep31EventProcessor.clearAutoAdvanceOptOuts()

  // PENDING_EXTERNAL would call ServiceContainer.sepHelper.rpcAction (which needs the real service
  // wiring and fails in a unit test), so returning normally proves the processor skipped the event.
  @Test
  fun `a status change for an opted-out transaction is skipped`() {
    assertTrue(Sep31EventProcessor.skipAutoAdvance(transactionId))
    val tx = GetTransactionResponse()
    tx.id = transactionId
    tx.status = SepTransactionStatus.PENDING_EXTERNAL
    val event =
      SendEventRequest(
        "event-id",
        "transaction_status_changed",
        "2026-10-09T00:00:00Z",
        SendEventRequestPayload(tx, null, null),
      )

    runBlocking { Sep31EventProcessor(mockk(), mockk()).onTransactionStatusChanged(event) }
  }
}
