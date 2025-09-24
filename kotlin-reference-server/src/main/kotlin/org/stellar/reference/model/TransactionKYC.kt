package org.stellar.reference.model

import kotlinx.serialization.SerialName

data class TransactionKYC(
  @SerialName("transaction_id") val transactionId: String,
  @SerialName("required_fields") val requiredFields: List<String> = emptyList(),
)
