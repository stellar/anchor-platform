package org.stellar.reference.event.processor

import java.time.Instant
import java.util.*
import kotlinx.coroutines.runBlocking
import org.stellar.anchor.api.callback.GetCustomerRequest
import org.stellar.anchor.api.callback.PutCustomerRequest
import org.stellar.anchor.api.platform.*
import org.stellar.anchor.api.platform.PatchTransactionsRequest
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind
import org.stellar.anchor.api.rpc.method.RpcMethod
import org.stellar.anchor.api.sep.SepTransactionStatus.*
import org.stellar.reference.callbacks.customer.CustomerService
import org.stellar.reference.client.PlatformClient
import org.stellar.reference.data.*
import org.stellar.reference.log
import org.stellar.reference.service.SepHelper
import org.stellar.reference.transactionWithRetry
import org.stellar.sdk.*

class Sep6EventProcessor(
  private val config: Config,
  private val server: Server,
  private val platformClient: PlatformClient,
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
        val keypair = KeyPair.fromSecretSeed(config.appSettings.secret)
        lateinit var stellarTxnId: String
        if (config.appSettings.custodyEnabled) {
          sepHelper.rpcAction(
            RpcMethod.DO_STELLAR_PAYMENT.toString(),
            DoStellarPaymentRequest(transactionId = transaction.id),
          )
        } else {
          transactionWithRetry {
            stellarTxnId =
              submitStellarTransaction(
                keypair.accountId,
                transaction.destinationAccount,
                Asset.create(transaction.amountExpected.asset.toAssetId()),
                // If no amount was specified at transaction initialization, assume the user
                // transferred 1 USD to the Anchor's bank account
                if (transaction.amountExpected.amount.equals("0")) {
                  "1"
                } else {
                  transaction.amountExpected.amount
                },
              )
          }
          onchainPayments[transaction.id] = stellarTxnId
          patchTransaction(
            PlatformTransactionData.builder()
              .id(transaction.id)
              .status(PENDING_STELLAR)
              .updatedAt(Instant.now())
              .build()
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
      PENDING_STELLAR ->
        sepHelper.rpcAction(
          RpcMethod.NOTIFY_ONCHAIN_FUNDS_SENT.toString(),
          NotifyOnchainFundsSentRequest(
            transactionId = transaction.id,
            message = "Funds sent to user",
            stellarTransactionId = onchainPayments[transaction.id]!!,
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
      Kind.DEPOSIT,
      Kind.DEPOSIT_EXCHANGE -> {
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
        val sourceAsset =
          when (transaction.kind) {
            Kind.DEPOSIT -> "iso4217:USD"
            Kind.DEPOSIT_EXCHANGE -> transaction.amountIn.asset
            else -> throw RuntimeException("Unsupported kind: ${transaction.kind}")
          }
        val isDepositExchange = transaction.kind == Kind.DEPOSIT_EXCHANGE
        val isUsdOrCadAsset = sourceAsset == "iso4217:USD" || sourceAsset == "iso4217:CAD"

        val instructions =
          when {
            isDepositExchange && isUsdOrCadAsset ->
              if (sourceAsset == "iso4217:USD") usdDepositInstructions else cadDepositInstructions
            isDepositExchange -> throw RuntimeException("Unsupported asset: $sourceAsset")
            else -> usdDepositInstructions
          }

        if (verifyKyc(customer.account, customer.memo, transaction.kind).isEmpty()) {
          runBlocking {
            if (transaction.amountExpected.amount != null) {
              // The amount was specified at transaction initialization
              sepHelper.rpcAction(
                RpcMethod.REQUEST_OFFCHAIN_FUNDS.toString(),
                RequestOffchainFundsRequest(
                  transactionId = transaction.id,
                  message = "Please deposit the amount to the following bank account",
                  amountIn =
                    AmountAssetRequest(
                      asset = sourceAsset,
                      amount = transaction.amountExpected.amount,
                    ),
                  amountOut =
                    AmountAssetRequest(
                      asset = transaction.amountExpected.asset,
                      amount = transaction.amountExpected.amount,
                    ),
                  feeDetails = FeeDetails(total = "0", asset = sourceAsset),
                  instructions = instructions,
                ),
              )
            } else {
              sepHelper.rpcAction(
                RpcMethod.REQUEST_OFFCHAIN_FUNDS.toString(),
                RequestOffchainFundsRequest(
                  transactionId = transaction.id,
                  message = "Please deposit to the following bank account",
                  amountIn = AmountAssetRequest(asset = sourceAsset, amount = "0"),
                  amountOut =
                    AmountAssetRequest(asset = transaction.amountExpected.asset, amount = "0"),
                  feeDetails = FeeDetails(total = "0", asset = sourceAsset),
                  instructions = instructions,
                ),
              )
            }
          }
        }
      }
      Kind.WITHDRAWAL,
      Kind.WITHDRAWAL_EXCHANGE -> {
        val destinationAsset =
          when (transaction.kind) {
            Kind.WITHDRAWAL -> "iso4217:USD"
            Kind.WITHDRAWAL_EXCHANGE -> transaction.amountOut.asset
            else -> throw RuntimeException("Unsupported kind: ${transaction.kind}")
          }
        if (verifyKyc(customer.account, customer.memo, transaction.kind).isEmpty()) {
          runBlocking {
            if (transaction.amountExpected.amount != null) {
              // The amount was specified at transaction initialization
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
            } else {
              sepHelper.rpcAction(
                RpcMethod.REQUEST_ONCHAIN_FUNDS.toString(),
                RequestOnchainFundsRequest(
                  transactionId = transaction.id,
                  message = "Please deposit to the following address",
                  amountIn = AmountAssetRequest(transaction.amountExpected.asset, "0"),
                  amountOut = AmountAssetRequest(destinationAsset, "0"),
                  feeDetails = FeeDetails("0", transaction.amountExpected.asset),
                ),
              )
            }
          }
        }
      }
      else -> {
        log.warn { "Received transaction created event with unsupported kind: ${transaction.kind}" }
      }
    }
  }

  private fun verifyKyc(sep10Account: String, sep10AccountMemo: String?, kind: Kind): List<String> {
    val customer = runBlocking {
      customerService.getCustomer(
        GetCustomerRequest.builder()
          .account(sep10Account)
          .memo(sep10AccountMemo)
          .memoType(if (sep10AccountMemo != null) "id" else null)
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
            customerType = "sep6"
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

  private fun submitStellarTransaction(
    source: String,
    destination: String,
    asset: Asset,
    amount: String,
  ): String {
    // TODO: use Kotlin wallet SDK
    val account = server.accounts().account(source)
    val transaction =
      TransactionBuilder(account, Network.TESTNET)
        .setBaseFee(100)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(60)).build()
        )
        .addOperation(PaymentOperation.Builder(destination, asset, amount).build())
        .build()
    transaction.sign(KeyPair.fromSecretSeed(config.appSettings.secret))
    val txnResponse = server.submitTransaction(transaction)
    if (!txnResponse.isSuccess) {
      throw RuntimeException("Error submitting transaction: ${txnResponse.extras.resultCodes}")
    }

    return txnResponse.hash
  }

  private suspend fun patchTransaction(data: PlatformTransactionData) {
    val request =
      PatchTransactionsRequest.builder()
        .records(listOf(PatchTransactionRequest.builder().transaction(data).build()))
        .build()
    platformClient.patchTransactions(request)
  }
}
