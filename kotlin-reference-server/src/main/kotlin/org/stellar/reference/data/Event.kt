package org.stellar.reference.data

import org.stellar.anchor.api.platform.GetQuoteResponse
import org.stellar.anchor.api.platform.GetTransactionResponse

data class SendEventRequest(
  val id: String,
  val type: String,
  val timestamp: String,
  val payload: SendEventRequestPayload
)

public data class SendEventRequestPayload(
  val transaction: GetTransactionResponse,
  val quote: GetQuoteResponse
)
