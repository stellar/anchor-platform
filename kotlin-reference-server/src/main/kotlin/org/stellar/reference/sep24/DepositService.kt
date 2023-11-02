package org.stellar.reference.sep24

import java.math.BigDecimal
import java.math.RoundingMode
import mu.KotlinLogging
import org.stellar.reference.data.*
import org.stellar.reference.service.SepHelper
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
    memoType: String?
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
        // 5. Sign and send transaction
        val txHash = sep24.sendStellarTransaction(account, asset, amount, memo, memoType)

        // 6. Finalize Stellar anchor transaction
        finalizeStellarTransaction(transactionId, txHash, asset, amount)
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
    val fee = calculateFee(amount)
    val stellarAsset = "stellar:$asset"

    if (cfg.appSettings.rpcEnabled) {
      sep24.rpcAction(
        "request_offchain_funds",
        RequestOffchainFundsRequest(
          transactionId = transactionId,
          message = "waiting on the user to transfer funds",
          amountIn = AmountAssetRequest(asset = "iso4217:USD", amount = amount.toPlainString()),
          amountOut =
            AmountAssetRequest(asset = stellarAsset, amount = amount.subtract(fee).toPlainString()),
          amountFee = AmountAssetRequest(asset = "iso4217:USD", amount = fee.toPlainString())
        )
      )
    } else {
      sep24.patchTransaction(
        PatchTransactionTransaction(
          transactionId,
          status = "pending_user_transfer_start",
          message = "waiting on the user to transfer funds",
          amountIn = Amount(amount.toPlainString(), stellarAsset),
          amountOut = Amount(amount.subtract(fee).toPlainString(), stellarAsset),
          amountFee = Amount(fee.toPlainString(), stellarAsset)
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
          message = "funds received, transaction is being processed"
        )
      )
    } else {
      sep24.patchTransaction(
        transactionId,
        "pending_anchor",
        "funds received, transaction is being processed"
      )
      sep24.patchTransaction(
        transactionId,
        "pending_stellar",
        "funds received, transaction is being processed"
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
    amount: BigDecimal
  ) {
    if (cfg.appSettings.rpcEnabled) {
      sep24.rpcAction(
        "notify_onchain_funds_sent",
        NotifyOnchainFundsSentRequest(
          transactionId = transactionId,
          stellarTransactionId = stellarTransactionId
        )
      )
    } else {
      val operationId: Long =
        sep24.server
          .operations()
          .forTransaction(stellarTransactionId)
          .execute()
          .records
          .filterIsInstance<PaymentOperationResponse>()
          .first()
          .id

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
                      Amount(amount.toPlainString(), asset)
                    )
                  )
              )
            )
        )
      )
    }
  }

  private suspend fun failTransaction(transactionId: String, message: String?) {
    if (cfg.appSettings.rpcEnabled) {
      sep24.rpcAction(
        "notify_transaction_error",
        NotifyTransactionErrorRequest(transactionId = transactionId, message = message)
      )
    } else {
      sep24.patchTransaction(transactionId, "error", message)
    }
  }

  // Set 10% fee
  private fun calculateFee(amount: BigDecimal): BigDecimal {
    val fee = amount.multiply(BigDecimal.valueOf(0.1))
    val scale = if (amount.scale() == 0) 1 else amount.scale()
    return fee.setScale(scale, RoundingMode.DOWN)
  }
}
