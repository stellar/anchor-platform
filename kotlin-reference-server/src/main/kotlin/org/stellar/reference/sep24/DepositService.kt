package org.stellar.reference.sep24

import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.single
import org.stellar.reference.data.*
import org.stellar.reference.service.SepHelper
import org.stellar.reference.transactionWithRetry
import org.stellar.sdk.responses.operations.InvokeHostFunctionOperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse

private val log = KotlinLogging.logger {}

class DepositService(private val cfg: Config) {

  val sep24 = SepHelper(cfg)

  suspend fun processDeposit(
    transactionId: String,
    amount: BigDecimal,
    account: String,
    asset: String,
    memo: String?,
    memoType: String?,
  ) {
    try {
      var transaction = sep24.getTransaction(transactionId)
      log.info { "Transaction found $transaction" }

      // 2. Wait for user to submit a transfer (e.g. Bank transfer)
      initiateTransfer(transactionId, amount, asset)

      transaction = sep24.getTransaction(transactionId)
      log.info { "Transaction status changed: $transaction" }

      // 4. Notify user transaction is being processed
      notifyTransactionProcessed(transactionId)

      transaction = sep24.getTransaction(transactionId)
      log.info { "Transaction status changed: $transaction" }

      if (cfg.appSettings.custodyEnabled) {
        // 5. Send Stellar transaction using Custody Server
        sendCustodyStellarTransaction(transactionId)

        // 6. Wait for Stellar transaction
        sep24.waitStellarTransaction(transactionId, "completed")

        // 7. Finalize custody Stellar anchor transaction
        finalizeCustodyStellarTransaction(transactionId)
      } else {
        transactionWithRetry {
          // 5. Sign and send transaction
          val txHash: String
          if (account.startsWith("G")) {
            txHash = sep24.sendStellarTransaction(account, asset, amount, memo, memoType)
          } else {
            txHash = sep24.sendSACTransferTransaction(account, asset, amount)
          }

          // 6. Finalize Stellar anchor transaction
          finalizeStellarTransaction(transactionId, txHash, asset, amount)
        }
      }

      log.info { "Transaction completed: $transactionId" }
    } catch (e: Exception) {
      log.error(e) { "Error happened during processing transaction $transactionId" }

      try {
        // If some error happens during the job, set anchor transaction to error status
        failTransaction(transactionId, e.message)
      } catch (e: Exception) {
        log.error(e) { "CRITICAL: failed to set transaction status to error" }
      }
    }
  }

  private suspend fun initiateTransfer(transactionId: String, amount: BigDecimal, asset: String) {
    val stellarAsset = "stellar:$asset"

    if (cfg.appSettings.rpcEnabled) {
      sep24.rpcAction(
        "request_offchain_funds",
        RequestOffchainFundsRequest(
          transactionId = transactionId,
          message = "waiting on the user to transfer funds",
          amountIn = AmountAssetRequest(asset = "iso4217:USD", amount = amount.toPlainString()),
          amountOut = AmountAssetRequest(asset = stellarAsset, amount = amount.toPlainString()),
          feeDetails = FeeDetails(total = "0", asset = "iso4217:USD"),
        ),
      )
    } else {
      sep24.patchTransaction(
        PatchTransactionTransaction(
          transactionId,
          status = "pending_user_transfer_start",
          message = "waiting on the user to transfer funds",
          amountIn = Amount(amount.toPlainString(), stellarAsset),
          amountOut = Amount(amount.toPlainString(), stellarAsset),
          feeDetails = FeeDetails(total = "0", stellarAsset),
        )
      )
    }
  }

  private suspend fun notifyTransactionProcessed(transactionId: String) {
    if (cfg.appSettings.rpcEnabled) {
      sep24.rpcAction(
        "notify_offchain_funds_received",
        NotifyOffchainFundsReceivedRequest(
          transactionId = transactionId,
          message = "funds received, transaction is being processed",
        ),
      )
    } else {
      sep24.patchTransaction(
        transactionId,
        "pending_anchor",
        "funds received, transaction is being processed",
      )
      sep24.patchTransaction(
        transactionId,
        "pending_stellar",
        "funds received, transaction is being processed",
      )
    }
  }

  private suspend fun sendCustodyStellarTransaction(transactionId: String) {
    if (cfg.appSettings.rpcEnabled) {
      sep24.rpcAction("do_stellar_payment", DoStellarPaymentRequest(transactionId = transactionId))
    } else {
      sep24.sendCustodyStellarTransaction(transactionId)
    }
  }

  private suspend fun finalizeCustodyStellarTransaction(transactionId: String) {
    if (!cfg.appSettings.rpcEnabled) {
      sep24.patchTransaction(
        PatchTransactionTransaction(transactionId, "completed", message = "completed")
      )
    }
  }

  private suspend fun finalizeStellarTransaction(
    transactionId: String,
    stellarTransactionId: String,
    asset: String,
    amount: BigDecimal,
  ) {
    if (cfg.appSettings.rpcEnabled) {
      sep24.rpcAction(
        "notify_onchain_funds_sent",
        NotifyOnchainFundsSentRequest(
          transactionId = transactionId,
          stellarTransactionId = stellarTransactionId,
        ),
      )
    } else {
      val operationId: Long = getFirstOperationIdWithRetry(stellarTransactionId)

      sep24.patchTransaction(
        PatchTransactionTransaction(
          transactionId,
          "completed",
          message = "completed",
          stellarTransactions =
            listOf(
              StellarTransaction(
                stellarTransactionId,
                payments =
                  listOf(
                    StellarPayment(
                      id = operationId.toString(),
                      Amount(amount.toPlainString(), asset),
                    )
                  ),
              )
            ),
        )
      )
    }
  }

  suspend fun getFirstOperationIdWithRetry(
    stellarTransactionId: String,
    maxAttempts: Int = 5,
    delaySeconds: Int = 5,
  ): Long {
    return flow {
        val operationId =
          sep24.server
            .operations()
            .forTransaction(stellarTransactionId)
            .execute()
            .records
            .filter { it is PaymentOperationResponse || it is InvokeHostFunctionOperationResponse }
            .first()
            .id
        emit(operationId)
      }
      .retryWhen { _, attempt ->
        if (attempt < maxAttempts) {
          delay(delaySeconds.seconds)
          true
        } else {
          false
        }
      }
      .single()
  }

  private suspend fun failTransaction(transactionId: String, message: String?) {
    if (cfg.appSettings.rpcEnabled) {
      sep24.rpcAction(
        "notify_transaction_error",
        NotifyTransactionErrorRequest(transactionId = transactionId, message = message),
      )
    } else {
      sep24.patchTransaction(transactionId, "error", message)
    }
  }
}
