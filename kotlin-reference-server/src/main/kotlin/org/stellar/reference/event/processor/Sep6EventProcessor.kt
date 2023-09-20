package org.stellar.reference.event.processor

import java.lang.RuntimeException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.platform.*
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.InstructionField
import org.stellar.anchor.api.shared.StellarPayment
import org.stellar.anchor.api.shared.StellarTransaction
import org.stellar.reference.client.PlatformClient
import org.stellar.reference.data.Config
import org.stellar.reference.log
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.PaymentOperation
import org.stellar.sdk.Server
import org.stellar.sdk.TransactionBuilder

class Sep6EventProcessor(
  private val config: Config,
  private val server: Server,
  private val platformClient: PlatformClient,
) : SepAnchorEventProcessor {
  override fun onQuoteCreated(event: AnchorEvent) {
    TODO("Not yet implemented")
  }

  override fun onTransactionCreated(event: AnchorEvent) {
    when (val kind = event.transaction.kind) {
      PlatformTransactionData.Kind.DEPOSIT -> onDepositTransactionCreated(event)
      PlatformTransactionData.Kind.WITHDRAWAL -> onWithdrawTransactionCreated(event)
      else -> {
        log.warn("Received transaction created event with unsupported kind: $kind")
      }
    }
  }

  private fun onDepositTransactionCreated(event: AnchorEvent) {
    if (event.transaction.status != SepTransactionStatus.INCOMPLETE) {
      log.warn(
        "Received deposit transaction created event with unsupported status: ${event.transaction.status}"
      )
      return
    }
    runBlocking {
      patchTransaction(
        PlatformTransactionData.builder()
          .id(event.transaction.id)
          .status(SepTransactionStatus.PENDING_ANCHOR)
          .build()
      )
    }
  }

  private fun onWithdrawTransactionCreated(event: AnchorEvent) {
    if (event.transaction.status != SepTransactionStatus.INCOMPLETE) {
      log.warn(
        "Received withdraw transaction created event with unsupported status: ${event.transaction.status}"
      )
      return
    }
    runBlocking {
      patchTransaction(
        PlatformTransactionData.builder()
          .id(event.transaction.id)
          .status(SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE)
          .build()
      )
    }
  }

  override fun onTransactionError(event: AnchorEvent) {
    log.warn("Received transaction error event: $event")
  }

  override fun onTransactionStatusChanged(event: AnchorEvent) {
    when (val kind = event.transaction.kind) {
      PlatformTransactionData.Kind.DEPOSIT -> onDepositTransactionStatusChanged(event)
      PlatformTransactionData.Kind.WITHDRAWAL -> onWithdrawTransactionStatusChanged(event)
      else -> {
        log.warn("Received transaction created event with unsupported kind: $kind")
      }
    }
  }

  private fun onDepositTransactionStatusChanged(event: AnchorEvent) {
    val transaction = event.transaction
    when (val status = transaction.status) {
      SepTransactionStatus.PENDING_ANCHOR -> {
        runBlocking {
          patchTransaction(
            PlatformTransactionData.builder()
              .id(transaction.id)
              .updatedAt(Instant.now())
              .status(SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE)
              .build()
          )
        }
      }
      SepTransactionStatus.COMPLETED -> {
        log.info("Transaction ${transaction.id} completed")
      }
      else -> {
        log.warn("Received transaction status changed event with unsupported status: $status")
      }
    }
  }

  private fun onWithdrawTransactionStatusChanged(event: AnchorEvent) {
    val transaction = event.transaction
    when (val status = transaction.status) {
      SepTransactionStatus.PENDING_ANCHOR -> {
        runBlocking {
          patchTransaction(
            PlatformTransactionData.builder()
              .id(transaction.id)
              .updatedAt(Instant.now())
              .status(SepTransactionStatus.COMPLETED)
              .build()
          )
        }
      }
      SepTransactionStatus.COMPLETED -> {
        log.info("Transaction ${transaction.id} completed")
      }
      else -> {
        log.warn("Received transaction status changed event with unsupported status: $status")
      }
    }
  }

  override fun onCustomerUpdated(event: AnchorEvent) {
    runBlocking {
        platformClient
          .getTransactions(
            GetTransactionsRequest.builder()
              .sep(TransactionsSeps.SEP_6)
              .orderBy(TransactionsOrderBy.CREATED_AT)
              .order(TransactionsOrder.ASC)
              .statuses(listOf(SepTransactionStatus.PENDING_CUSTOMER_INFO_UPDATE))
              .build()
          )
          .records
      }
      .forEach { transaction ->
        when (transaction.kind) {
          PlatformTransactionData.Kind.DEPOSIT -> handleDepositTransaction(transaction)
          PlatformTransactionData.Kind.WITHDRAWAL -> handleWithdrawTransaction(transaction)
          else -> {
            log.warn(
              "Received transaction created event with unsupported kind: ${transaction.kind}"
            )
          }
        }
      }
  }

  private fun handleDepositTransaction(transaction: GetTransactionResponse) {
    val keypair = KeyPair.fromSecretSeed(config.appSettings.secret)
    val assetCode = transaction.amountExpected.asset.toAssetId()

    val asset = Asset.create(assetCode)
    val amount = transaction.amountExpected.amount
    val destination = transaction.destinationAccount

    val stellarTxn = submitStellarTransaction(keypair.accountId, destination, asset, amount)
    runBlocking {
      patchTransaction(
        PlatformTransactionData.builder()
          .id(transaction.id)
          .status(SepTransactionStatus.COMPLETED)
          .updatedAt(Instant.now())
          .completedAt(Instant.now())
          .requiredInfoMessage(null)
          .requiredInfoUpdates(null)
          .requiredCustomerInfoUpdates(null)
          .requiredCustomerInfoUpdates(null)
          .instructions(
            mapOf(
              "organization.bank_number" to
                InstructionField.builder()
                  .value("121122676")
                  .description("US Bank routing number")
                  .build(),
              "organization.bank_account_number" to
                InstructionField.builder()
                  .value("13719713158835300")
                  .description("US Bank account number")
                  .build(),
            )
          )
          .stellarTransactions(listOf(stellarTxn))
          .build()
      )
    }
  }

  private fun handleWithdrawTransaction(transaction: GetTransactionResponse) {
    runBlocking {
      patchTransaction(
        PlatformTransactionData.builder()
          .id(transaction.id)
          .status(SepTransactionStatus.PENDING_USR_TRANSFER_START)
          .updatedAt(Instant.now())
          .completedAt(Instant.now())
          .requiredInfoMessage(null)
          .requiredInfoUpdates(null)
          .requiredCustomerInfoUpdates(null)
          .requiredCustomerInfoUpdates(null)
          .build()
      )
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
    amount: String
  ): StellarTransaction {
    // TODO: use Kotlin wallet SDK
    val account = server.accounts().account(source)
    val transaction =
      TransactionBuilder(account, Network.TESTNET)
        .setBaseFee(100)
        .setTimeout(60L)
        .addOperation(PaymentOperation.Builder(destination, asset, amount).build())
        .build()
    transaction.sign(KeyPair.fromSecretSeed(config.appSettings.secret))
    val txnResponse = server.submitTransaction(transaction)
    if (!txnResponse.isSuccess) {
      throw RuntimeException("Error submitting transaction: ${txnResponse.extras.resultCodes}")
    }
    val txHash = txnResponse.hash
    val operationId = server.operations().forTransaction(txHash).execute().records.firstOrNull()?.id
    val stellarPayment =
      StellarPayment(
        operationId.toString(),
        Amount(amount, asset.toString()),
        StellarPayment.Type.PAYMENT,
        source,
        destination
      )
    return StellarTransaction.builder().id(txHash).payments(listOf(stellarPayment)).build()
  }

  private suspend fun patchTransaction(data: PlatformTransactionData) {
    val request =
      PatchTransactionsRequest.builder()
        .records(listOf(PatchTransactionRequest.builder().transaction(data).build()))
        .build()
    platformClient.patchTransactions(request)
  }
}
