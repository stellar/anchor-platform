package org.stellar.reference.event.processor

import java.util.*
import kotlinx.coroutines.runBlocking
import org.stellar.anchor.api.callback.GetCustomerRequest
import org.stellar.anchor.api.platform.*
import org.stellar.anchor.api.rpc.method.RpcMethod
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.reference.client.PlatformClient
import org.stellar.reference.data.*
import org.stellar.reference.di.ServiceContainer.customerService
import org.stellar.reference.di.ServiceContainer.sepHelper
import org.stellar.reference.log

class Sep31EventProcessor(
  private val config: Config,
  private val platformClient: PlatformClient,
) : SepAnchorEventProcessor {
  companion object {
    val requiredKyc =
      listOf("bank_account_number", "bank_account_type", "bank_number", "bank_branch_number")
  }

  override suspend fun onQuoteCreated(event: SendEventRequest) {
    TODO("Not yet implemented")
  }

  override suspend fun onTransactionCreated(event: SendEventRequest) {
    log.info { "Transaction ${event.payload.transaction!!.id} is created" }

    val (memo, memoType) =
      if (
        config.appSettings.distributionWalletMemo.isBlank() ||
          config.appSettings.distributionWalletMemoType.isBlank()
      ) {
        Pair((10000..20000).random().toString(), "id")
      } else {
        Pair(
          config.appSettings.distributionWalletMemo,
          config.appSettings.distributionWalletMemoType
        )
      }

    sepHelper.rpcAction(
      RpcMethod.REQUEST_ONCHAIN_FUNDS.toString(),
      RequestOnchainFundsRequest(
        transactionId = event.payload.transaction!!.id,
        message = "Transaction created",
        destinationAccount = config.appSettings.distributionWallet,
        memoType = memoType,
        memo = memo,
      ),
    )
  }

  override suspend fun onTransactionStatusChanged(event: SendEventRequest) {
    val transaction = event.payload.transaction!!
    when (val status = transaction.status) {
      PENDING_SENDER -> {
        log.info { "Transaction ${transaction.id} is in pending_sender status" }
      }
      PENDING_RECEIVER -> {
        if (verifyKyc(transaction).isNotEmpty()) {
          requestKyc(event)
          return
        }
        sendExternal(transaction.id)
      }
      PENDING_EXTERNAL ->
        sepHelper.rpcAction(
          RpcMethod.NOTIFY_OFFCHAIN_FUNDS_SENT.toString(),
          NotifyOffchainFundsSentRequest(
            transactionId = transaction.id,
            message = "Funds sent to receiver",
          ),
        )
      COMPLETED -> {
        log.info { "Transaction ${transaction.id} is completed" }
      }
      else -> {
        log.warn { "Received transaction status changed event with unsupported status: $status" }
      }
    }
  }

  override suspend fun onCustomerUpdated(event: SendEventRequest) {
    platformClient
      .getTransactions(
        GetTransactionsRequest.builder()
          .sep(TransactionsSeps.SEP_31)
          .orderBy(TransactionsOrderBy.CREATED_AT)
          .order(TransactionsOrder.ASC)
          .statuses(listOf(PENDING_CUSTOMER_INFO_UPDATE))
          .build()
      )
      .records
      .forEach { notifyCustomerUpdated(it) }
  }

  private fun notifyCustomerUpdated(transaction: GetTransactionResponse) {
    runBlocking {
      if (verifyKyc(transaction).isEmpty()) {
        // KYC is complete
        sepHelper.rpcAction(
          RpcMethod.NOTIFY_CUSTOMER_INFO_UPDATED.toString(),
          NotifyCustomerInfoUpdatedRequest(
            transactionId = transaction.id,
            message = "Customer info updated",
          ),
        )
      }
    }
  }

  private fun verifyKyc(transaction: GetTransactionResponse): List<String> {
    val receiver = runBlocking {
      customerService.getCustomer(
        GetCustomerRequest.builder().transactionId(transaction.id).type("sep31-receiver").build()
      )
    }
    val providedFields = receiver.providedFields.keys
    return requiredKyc.filter { !providedFields.contains(it) }
  }

  private fun requestKyc(event: SendEventRequest) {
    val customer = event.payload.transaction!!.customers.receiver
    val missingFields = verifyKyc(event.payload.transaction)
    runBlocking {
      if (missingFields.isNotEmpty()) {
        customerService.requestAdditionalFieldsForTransaction(
          event.payload.transaction.id,
          missingFields,
        )
        sepHelper.rpcAction(
          RpcMethod.NOTIFY_CUSTOMER_INFO_UPDATED.toString(),
          NotifyCustomerInfoUpdatedRequest(
            transactionId = event.payload.transaction.id,
            message = "Please update your info",
            customerId = customer.id,
            customerType = "sep31-receiver",
          ),
        )
      }
    }
  }

  private fun sendExternal(transactionId: String) {
    runBlocking {
      sepHelper.rpcAction(
        "notify_offchain_funds_sent",
        NotifyOffchainFundsSentRequest(
          transactionId = transactionId,
          message = "external transfer sent"
        )
      )
    }
  }
}
