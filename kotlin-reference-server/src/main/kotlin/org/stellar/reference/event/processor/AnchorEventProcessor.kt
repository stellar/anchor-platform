package org.stellar.reference.event.processor

import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.reference.data.SendEventRequest
import org.stellar.reference.log

class AnchorEventProcessor(
  private val sep6EventProcessor: Sep6EventProcessor,
  private val sep31EventProcessor: Sep31EventProcessor,
  private val noOpEventProcessor: NoOpEventProcessor,
) {
  suspend fun handleEvent(event: SendEventRequest) {
    val processor = getProcessor(event)
    try {
      when (event.type) {
        AnchorEvent.Type.TRANSACTION_CREATED.type -> {
          log.info { "Received transaction created event" }
          processor.onTransactionCreated(event)
        }
        AnchorEvent.Type.TRANSACTION_STATUS_CHANGED.type -> {
          log.info { "Received transaction status changed event" }
          processor.onTransactionStatusChanged(event)
        }
        AnchorEvent.Type.CUSTOMER_UPDATED.type -> {
          log.info { "Received customer updated event" }
          sep6EventProcessor.onCustomerUpdated(event)
          sep31EventProcessor.onCustomerUpdated(event)
        }
        AnchorEvent.Type.QUOTE_CREATED.type -> {
          log.info { "Received quote created event" }
          processor.onQuoteCreated(event)
        }
        else -> {
          log.warn {
            "Received event of type ${event.type} which is not supported by the reference server"
          }
        }
      }
    } catch (e: Exception) {
      log.error(e) { "Error processing event: $event" }
    }
  }

  private fun getProcessor(event: SendEventRequest): SepAnchorEventProcessor =
    when (event.payload.transaction?.sep) {
      PlatformTransactionData.Sep.SEP_6 -> sep6EventProcessor
      PlatformTransactionData.Sep.SEP_31 -> sep31EventProcessor
      else -> noOpEventProcessor
    }
}
