package org.stellar.reference.event.processor

import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.stellar.anchor.api.callback.GetCustomerRequest
import org.stellar.anchor.api.callback.PutCustomerRequest
import org.stellar.anchor.api.platform.*
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind
import org.stellar.anchor.api.rpc.method.RpcMethod
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.reference.callbacks.customer.CustomerService
import org.stellar.reference.client.PaymentClient
import org.stellar.reference.client.PlatformClient
import org.stellar.reference.data.*
import org.stellar.reference.log
import org.stellar.reference.service.SepHelper
import org.stellar.reference.transactionWithRetry
import org.stellar.sdk.Asset
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse.GetTransactionStatus.SUCCESS

class Sep6EventProcessor(
  private val config: Config,
  private val platformClient: PlatformClient,
  private val paymentClient: PaymentClient,
  private val customerService: CustomerService,
  private val sepHelper: SepHelper,
  /** Map of transaction ID to Stellar transaction ID. */
  private val onchainPayments: MutableMap<String, String> = mutableMapOf(),
  /** Map of transaction ID to external transaction ID. */
  private val offchainPayments: MutableMap<String, String> = mutableMapOf(),
) : SepAnchorEventProcessor {
  companion object {
    val requiredKyc =
      listOf(
        "birth_date",
        "id_type",
        "id_country_code",
        "id_issue_date",
        "id_expiration_date",
        "id_number",
      )
    val depositRequiredKyc = listOf("address")
    val withdrawRequiredKyc =
      listOf("bank_account_number", "bank_account_type", "bank_number", "bank_branch_number")

    val usdDepositInstructions =
      mapOf(
        "organization.bank_number" to
          InstructionField(value = "121122676", description = "US Bank routing number"),
        "organization.bank_account_number" to
          InstructionField(value = "13719713158835300", description = "US Bank account number"),
      )
    val cadDepositInstructions =
      mapOf(
        "organization.bank_number" to
          InstructionField(value = "121122676", description = "CA Bank routing number"),
        "organization.bank_account_number" to
          InstructionField(value = "13719713158835300", description = "CA Bank account number"),
      )
  }

  override suspend fun onQuoteCreated(event: SendEventRequest) {
    TODO("Not yet implemented")
  }

  override suspend fun onTransactionCreated(event: SendEventRequest) {
    when (val kind = event.payload.transaction!!.kind) {
      Kind.DEPOSIT,
      Kind.DEPOSIT_EXCHANGE,
      Kind.WITHDRAWAL,
      Kind.WITHDRAWAL_EXCHANGE -> {
        requestKyc(event)
        try {
          requestCustomerFunds(event.payload.transaction)
        } catch (e: Exception) {
          log.error(e) { "Error requesting customer funds" }
        }
      }
      else -> {
        log.warn { "Received transaction created event with unsupported kind: $kind" }
      }
    }
  }

  override suspend fun onTransactionStatusChanged(event: SendEventRequest) {
    when (val kind = event.payload.transaction!!.kind) {
      Kind.DEPOSIT,
      Kind.DEPOSIT_EXCHANGE -> onDepositTransactionStatusChanged(event)
      Kind.WITHDRAWAL,
      Kind.WITHDRAWAL_EXCHANGE -> onWithdrawTransactionStatusChanged(event)
      else -> {
        log.warn { "Received transaction created event with unsupported kind: $kind" }
      }
    }
  }

  private suspend fun onDepositTransactionStatusChanged(event: SendEventRequest) {
    val transaction = event.payload.transaction!!
    when (val status = transaction.status) {
      PENDING_ANCHOR -> {
        val customer = transaction.customers.sender
        if (verifyKyc(customer.account, customer.memo, transaction.kind).isNotEmpty()) {
          requestKyc(event)
          return
        }

        lateinit var stellarTxnId: String
        if (config.appSettings.custodyEnabled) {
          sepHelper.rpcAction(
            RpcMethod.DO_STELLAR_PAYMENT.toString(),
            DoStellarPaymentRequest(transactionId = transaction.id),
          )
        } else {
          transactionWithRetry {
            stellarTxnId =
              paymentClient.send(
                transaction.destinationAccount,
                Asset.create(transaction.amountExpected.asset.toAssetId()),
                // If no amount was specified at transaction initialization, assume the user
                // transferred 1 USD to the Anchor's bank account
                if (transaction.amountExpected.amount.equals("0")) {
                  "1"
                } else {
                  transaction.amountOut.amount
                },
              )
          }
          onchainPayments[transaction.id] = stellarTxnId

          log.info { "Waiting for transaction $stellarTxnId to be available..." }
          run loop@{
            repeat(5) { attempt ->
              try {
                val resp = paymentClient.getTransaction(stellarTxnId)
                if (resp != null && resp.status == SUCCESS) return@loop
              } catch (e: Exception) {
                log.warn(e) { "Attempt ${attempt + 1}: Failed to fetch transaction $stellarTxnId" }
              }
              delay(2_000)
            }
          }

          // After the transaction is available, call notify_onchain_funds_sent
          sepHelper.rpcAction(
            RpcMethod.NOTIFY_ONCHAIN_FUNDS_SENT.toString(),
            NotifyOnchainFundsSentRequest(
              transactionId = transaction.id,
              message = "Funds sent to user",
              stellarTransactionId = onchainPayments[transaction.id]!!,
            ),
          )
        }
      }
      PENDING_USR_TRANSFER_START ->
        sepHelper.rpcAction(
          RpcMethod.NOTIFY_OFFCHAIN_FUNDS_RECEIVED.toString(),
          NotifyOffchainFundsReceivedRequest(
            transactionId = transaction.id,
            message = "Funds received from user",
          ),
        )
      COMPLETED -> {
        log.info { "Transaction ${transaction.id} completed" }
      }
      else -> {
        log.warn { "Received transaction status changed event with unsupported status: $status" }
      }
    }
  }

  private suspend fun onWithdrawTransactionStatusChanged(event: SendEventRequest) {
    val transaction = event.payload.transaction!!
    when (val status = transaction.status) {
      PENDING_ANCHOR -> {
        val customer = transaction.customers.sender
        if (verifyKyc(customer.account, customer.memo, Kind.WITHDRAWAL).isNotEmpty()) {
          requestKyc(event)
          return
        }
        if (offchainPayments[transaction.id] == null && transaction.transferReceivedAt != null) {
          // If the amount was not specified at transaction initialization, set the
          // amountOut and amountFee fields after receiving the onchain deposit.
          if (transaction.amountOut.amount.equals("0")) {
            sepHelper.rpcAction(
              RpcMethod.NOTIFY_AMOUNTS_UPDATED.toString(),
              NotifyAmountsUpdatedRequest(
                transactionId = transaction.id,
                amountOut = AmountRequest(amount = transaction.amountIn.amount),
                feeDetails = FeeDetails(total = "0", asset = transaction.amountExpected.asset),
              ),
            )
          }
          val externalTxnId = UUID.randomUUID()
          offchainPayments[transaction.id] = externalTxnId.toString()
          sepHelper.rpcAction(
            RpcMethod.NOTIFY_OFFCHAIN_FUNDS_PENDING.toString(),
            NotifyOffchainFundsPendingRequest(
              transactionId = transaction.id,
              message = "Funds sent to user",
              externalTransactionId = externalTxnId.toString(),
            ),
          )
        } else if (transaction.transferReceivedAt != null) {
          sepHelper.rpcAction(
            RpcMethod.NOTIFY_OFFCHAIN_FUNDS_AVAILABLE.toString(),
            NotifyOffchainFundsAvailableRequest(
              transactionId = transaction.id,
              message = "Funds available for withdrawal",
              externalTransactionId = offchainPayments[transaction.id]!!,
            ),
          )
        }
      }
      PENDING_EXTERNAL ->
        runBlocking {
          sepHelper.rpcAction(
            RpcMethod.NOTIFY_OFFCHAIN_FUNDS_SENT.toString(),
            NotifyOffchainFundsSentRequest(
              transactionId = transaction.id,
              message = "Funds sent to user",
            ),
          )
        }
      COMPLETED -> {
        log.info { "Transaction ${transaction.id} completed" }
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
          .sep(TransactionsSeps.SEP_6)
          .orderBy(TransactionsOrderBy.CREATED_AT)
          .order(TransactionsOrder.ASC)
          .statuses(listOf(PENDING_CUSTOMER_INFO_UPDATE))
          .build()
      )
      .records
      .forEach { requestCustomerFunds(it) }
  }

  private fun requestCustomerFunds(transaction: GetTransactionResponse) {
    val customer = transaction.customers.sender
    when (transaction.kind) {
      Kind.DEPOSIT -> {
        val sourceAsset = "iso4217:USD"
        if (verifyKyc(customer.account, customer.memo, transaction.kind).isEmpty()) {
          runBlocking {
            // In deposit flow, If amount is specified, anchor can request that amount;
            // amount is either provided at transaction initialization or updated during KYC.
            sepHelper.rpcAction(
              RpcMethod.REQUEST_OFFCHAIN_FUNDS.toString(),
              RequestOffchainFundsRequest(
                transactionId = transaction.id,
                message = "Please deposit the amount to the following bank account",
                // amc
                amountIn =
                  transaction.amountExpected.amount?.let {
                    AmountAssetRequest(
                      asset = sourceAsset,
                      amount = transaction.amountExpected.amount ?: "0",
                    )
                  },
                amountOut =
                  transaction.amountExpected.amount?.let {
                    AmountAssetRequest(
                      asset = transaction.amountExpected.asset,
                      amount = transaction.amountExpected.amount ?: "0",
                    )
                  },
                feeDetails = FeeDetails(total = "0", asset = sourceAsset),
                instructions = usdDepositInstructions,
              ),
            )
          }
        }
      }
      Kind.DEPOSIT_EXCHANGE -> {
        val sourceAsset = transaction.amountIn.asset
        val instructions =
          mapOf("iso4217:USD" to usdDepositInstructions, "iso4217:CAD" to cadDepositInstructions)[
            sourceAsset]
            ?: throw RuntimeException("Unsupported asset: $sourceAsset")

        if (verifyKyc(customer.account, customer.memo, transaction.kind).isEmpty()) {
          runBlocking {
            // In deposit-exchange flow, amount, sourceAsset and destinationAsset are always
            // specified.
            sepHelper.rpcAction(
              RpcMethod.REQUEST_OFFCHAIN_FUNDS.toString(),
              RequestOffchainFundsRequest(
                transactionId = transaction.id,
                message = "Please deposit the amount to the following bank account",
                amountIn =
                  AmountAssetRequest(
                    asset = transaction.amountIn.asset,
                    // amountIn is always specified equal to amountExpected
                    amount = transaction.amountIn.amount,
                  ),
                amountOut =
                  AmountAssetRequest(
                    asset = transaction.amountOut.asset,
                    // amountOut.amount == "0" means no firm quote was provided, thus changing the
                    // amountOut to amountExpected
                    amount =
                      if (transaction.amountOut.amount == "0") {
                        transaction.amountExpected.amount
                      } else {
                        transaction.amountOut.amount
                      },
                  ),
                feeDetails =
                  FeeDetails(
                    total = transaction.feeDetails.total ?: "0",
                    asset = transaction.amountIn.asset,
                  ),
                instructions = instructions,
              ),
            )
          }
        }
      }
      Kind.WITHDRAWAL -> {
        val destinationAsset = "iso4217:USD"
        if (verifyKyc(customer.account, customer.memo, transaction.kind).isEmpty()) {
          runBlocking {
            sepHelper.rpcAction(
              RpcMethod.REQUEST_ONCHAIN_FUNDS.toString(),
              RequestOnchainFundsRequest(
                transactionId = transaction.id,
                message = "Please deposit the amount to the following address",
                amountIn =
                  AmountAssetRequest(
                    asset = transaction.amountExpected.asset,
                    amount = transaction.amountExpected.amount,
                  ),
                amountOut =
                  AmountAssetRequest(
                    asset = destinationAsset,
                    amount = transaction.amountExpected.amount,
                  ),
                feeDetails = FeeDetails(total = "0", asset = transaction.amountExpected.asset),
              ),
            )
          }
        }
      }
      Kind.WITHDRAWAL_EXCHANGE -> {
        val destinationAsset = transaction.amountOut.asset
        if (verifyKyc(customer.account, customer.memo, transaction.kind).isEmpty()) {
          runBlocking {
            // The amount was specified at transaction initialization
            sepHelper.rpcAction(
              RpcMethod.REQUEST_ONCHAIN_FUNDS.toString(),
              RequestOnchainFundsRequest(
                transactionId = transaction.id,
                message = "Please deposit the amount to the following address",
                amountIn =
                  AmountAssetRequest(
                    asset = transaction.amountIn.asset,
                    amount = transaction.amountIn.amount,
                  ),
                amountOut =
                  AmountAssetRequest(
                    asset = destinationAsset,
                    amount =
                      if (transaction.amountOut.amount == "0") {
                        transaction.amountExpected.amount
                      } else {
                        transaction.amountOut.amount
                      },
                  ),
                feeDetails =
                  FeeDetails(
                    total = transaction.feeDetails.total ?: "0",
                    asset = transaction.amountIn.asset,
                  ),
              ),
            )
          }
        }
      }
      else -> {
        log.warn { "Received transaction created event with unsupported kind: ${transaction.kind}" }
      }
    }
  }

  private fun verifyKyc(
    webAuthAccount: String,
    webAuthAccountMemo: String?,
    kind: Kind,
  ): List<String> {
    val customer = runBlocking {
      customerService.getCustomer(
        GetCustomerRequest.builder()
          .account(webAuthAccount)
          .memo(webAuthAccountMemo)
          .memoType(if (webAuthAccountMemo != null) "id" else null)
          .build()
      )
    }
    val providedFields = customer.providedFields.keys
    return requiredKyc
      .plus(
        if (kind == Kind.DEPOSIT || kind == Kind.DEPOSIT_EXCHANGE) depositRequiredKyc
        else withdrawRequiredKyc
      )
      .filter { !providedFields.contains(it) }
  }

  private fun requestKyc(event: SendEventRequest) {
    val kind = event.payload.transaction!!.kind
    val customer = event.payload.transaction.customers.sender
    val missingFields = verifyKyc(customer.account, customer.memo, kind)
    runBlocking {
      if (missingFields.isNotEmpty()) {
        customerService.requestAdditionalFieldsForTransaction(
          event.payload.transaction.id,
          missingFields,
        )
        val memoType = if (customer.memo != null) "id" else null
        var existingCustomerId =
          customerService
            .getCustomer(
              GetCustomerRequest.builder()
                .account(customer.account)
                .memo(customer.memo)
                .memoType(memoType)
                .build()
            )
            .id
        if (existingCustomerId == null) {
          existingCustomerId =
            customerService
              .upsertCustomer(
                PutCustomerRequest.builder()
                  .account(customer.account)
                  .memo(customer.memo)
                  .memoType(memoType)
                  .build()
              )
              .id
        }
        sepHelper.rpcAction(
          RpcMethod.NOTIFY_CUSTOMER_INFO_UPDATED.toString(),
          NotifyCustomerInfoUpdatedRequest(
            transactionId = event.payload.transaction.id,
            message = "Please update your info",
            customerId = existingCustomerId,
            customerType = "sep6",
          ),
        )
      }
    }
  }

  private fun String.toAssetId(): String {
    val parts = this.split(":")
    return when (parts.size) {
      3 -> "${parts[1]}:${parts[2]}"
      2 -> parts[1]
      else -> throw RuntimeException("Invalid asset format: $this")
    }
  }
}
