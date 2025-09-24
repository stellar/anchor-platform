package org.stellar.reference.data

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Transaction(
  val id: String,
  val status: String,
  val kind: String,
  val message: String? = null,
  @SerialName("amount_in") val amountIn: Amount? = null,
  @SerialName("amount_out") val amountOut: Amount? = null,
  @SerialName("amount_fee") val amountFee: Amount? = null,
  @SerialName("amount_expected") val amountExpected: Amount? = null,
  @SerialName("memo") val memo: String? = null,
  @SerialName("memo_type") val memoType: String? = null,
  @SerialName("source_account") var sourceAccount: String? = null,
  @SerialName("destination_account") var destinationAccount: String? = null,
  @SerialName("stellar_transactions") val stellarTransactions: List<StellarTransaction>? = null,
  val customers: Customers? = null,
)

@Serializable data class Customers(val sender: StellarId?, val receiver: StellarId?)

@Serializable
data class StellarId(val id: String? = null, val account: String? = null, val memo: String? = null)

@Serializable data class PatchTransactionsRequest(val records: List<PatchTransactionRecord>)

@Serializable data class PatchTransactionRecord(val transaction: PatchTransactionTransaction)

@Serializable
data class PatchTransactionTransaction(
  val id: String,
  val status: String,
  val message: String? = null,
  @SerialName("amount_in") val amountIn: Amount? = null,
  @SerialName("amount_out") val amountOut: Amount? = null,
  @SerialName("amount_fee") val amountFee: Amount? = null,
  @SerialName("stellar_transactions") val stellarTransactions: List<StellarTransaction>? = null,
  val memo: String? = null,
  @SerialName("memo_type") val memoType: String? = null,
)

@Serializable
data class RpcResponse(
  val id: String,
  val jsonrpc: String,
  @Contextual val result: Any?,
  @Contextual val error: Any?,
)

@Serializable
data class RpcRequest(
  val id: String,
  val jsonrpc: String,
  val method: String,
  val params: RpcActionParamsRequest,
)

@Serializable
sealed class RpcActionParamsRequest {
  @SerialName("transaction_id") abstract val transactionId: String
  abstract val message: String?
}

@Serializable
data class RequestOffchainFundsRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String,
  @SerialName("amount_in") val amountIn: AmountAssetRequest? = null,
  @SerialName("amount_out") val amountOut: AmountAssetRequest? = null,
  @SerialName("amount_fee") val amountFee: AmountAssetRequest? = null,
  @SerialName("amount_expected") val amountExpected: AmountRequest? = null,
  @SerialName("instructions") val instructions: Map<String, InstructionField>? = null,
) : RpcActionParamsRequest()

@Serializable
data class RequestOnchainFundsRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String,
  @SerialName("amount_in") val amountIn: AmountAssetRequest? = null,
  @SerialName("amount_out") val amountOut: AmountAssetRequest? = null,
  @SerialName("amount_fee") val amountFee: AmountAssetRequest? = null,
  @SerialName("amount_expected") val amountExpected: AmountRequest? = null,
) : RpcActionParamsRequest()

@Serializable
data class RequestCustomerInfoUpdateHandler(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String?,
  @SerialName("required_customer_info_message") val requiredCustomerInfoMessage: String? = null,
  @SerialName("required_customer_info_updates")
  val requiredCustomerInfoUpdates: List<String>? = null,
) : RpcActionParamsRequest()

@Serializable
data class NotifyCustomerInfoUpdatedRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String? = null,
  @SerialName("customer_id") val customerId: String? = null,
  @SerialName("customer_type") val customerType: String? = null
) : RpcActionParamsRequest()

@Serializable
data class NotifyOnchainFundsSentRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String? = null,
  @SerialName("stellar_transaction_id") val stellarTransactionId: String,
) : RpcActionParamsRequest()

@Serializable
data class NotifyOffchainFundsReceivedRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String,
  @SerialName("funds_received_at") val fundsReceivedAt: String? = null,
  @SerialName("external_transaction_id") val externalTransactionId: String? = null,
  @SerialName("amount_in") val amountIn: AmountAssetRequest? = null,
  @SerialName("amount_out") val amountOut: AmountAssetRequest? = null,
  @SerialName("amount_fee") val amountFee: AmountAssetRequest? = null,
) : RpcActionParamsRequest()

@Serializable
data class NotifyOffchainFundsAvailableRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String? = null,
  @SerialName("external_transaction_id") val externalTransactionId: String? = null,
) : RpcActionParamsRequest()

@Serializable
data class NotifyOffchainFundsPendingRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String? = null,
  @SerialName("external_transaction_id") val externalTransactionId: String? = null,
) : RpcActionParamsRequest()

@Serializable
data class NotifyAmountsUpdatedRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String? = null,
  @SerialName("amount_out") val amountOut: AmountRequest,
  @SerialName("amount_fee") val amountFee: AmountRequest,
) : RpcActionParamsRequest()

@Serializable
data class NotifyOffchainFundsSentRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String,
  @SerialName("funds_sent_at") val fundsReceivedAt: String? = null,
  @SerialName("external_transaction_id") val externalTransactionId: String? = null,
) : RpcActionParamsRequest()

@Serializable
data class DoStellarPaymentRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String? = null,
) : RpcActionParamsRequest()

@Serializable
data class NotifyTransactionErrorRequest(
  @SerialName("transaction_id") override val transactionId: String,
  override val message: String? = null,
) : RpcActionParamsRequest()

@Serializable data class AmountAssetRequest(val asset: String, val amount: String)

@Serializable data class AmountRequest(val amount: String)

@Serializable data class Amount(val amount: String? = null, val asset: String? = null)

@Serializable data class InstructionField(val value: String, val description: String)

class JwtToken(
  val transactionId: String,
  var expiration: Long, // Expiration Time
  var data: Map<String, String>,
)

@Serializable
data class DepositRequest(
  val amount: String,
  val name: String,
  val surname: String,
  val email: String,
)

@Serializable
data class WithdrawalRequest(
  val amount: String,
  val name: String,
  val surname: String,
  val email: String,
  val bank: String,
  val account: String,
)

@Serializable
data class StellarTransaction(
  val id: String,
  val memo: String? = null,
  @SerialName("memo_type") val memoType: String? = null,
  val payments: List<StellarPayment>,
)

@Serializable data class StellarPayment(val id: String, val amount: Amount)
