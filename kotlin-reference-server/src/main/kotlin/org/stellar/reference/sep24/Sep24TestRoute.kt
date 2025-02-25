package org.stellar.reference.sep24

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.stellar.reference.ClientException
import org.stellar.reference.jwt.JwtDecoder
import org.stellar.reference.service.SepHelper

private val log = KotlinLogging.logger {}

fun Route.testSep24(
  sep24: SepHelper,
  depositService: DepositService,
  withdrawalService: WithdrawalService,
  jwtKey: String,
) {
  route("/sep24/interactive") {
    get {
      log.info {
        "Called /sep24/interactive with parameters ${call.request.queryParameters.entries()}"
      }

      val token =
        JwtDecoder.decode(
          call.request.queryParameters["token"]
            ?: throw ClientException("Missing token parameter in the request"),
          jwtKey,
        )

      val transactionId = token.transactionId

      if (token.expiration > System.currentTimeMillis()) {
        throw ClientException("Token expired")
      }

      val transaction = sep24.getTransaction(transactionId)
      var amountExpected = token.data["amount"]?.toBigDecimal()
      if (amountExpected == null) {
        log.info {
          "Missing amountExpected.amount field. Using default value: 10 to simulate the amount was entered by the user in the interactive flow."
        }
        amountExpected = "10".toBigDecimal()
      }
      try {
        when (transaction.kind.lowercase()) {
          "deposit" -> {
            val account =
              transaction.destinationAccount
                ?: throw ClientException("Missing destination_account field")
            val asset =
              token.data["asset"] ?: throw ClientException("Missing amountExpected.asset field")
            val memo = transaction.memo
            val memoType = transaction.memoType

            if (!asset.startsWith("stellar:")) {
              throw ClientException("Invalid asset format")
            }

            call.respondText("The sep24 interactive deposit has been successfully started.")

            val stellarAsset = asset.replace("stellar:", "")

            // Run deposit processing asynchronously
            CoroutineScope(Job()).launch {
              depositService.processDeposit(
                transactionId,
                amountExpected,
                account,
                stellarAsset,
                memo,
                memoType,
              )
            }
          }
          "withdrawal" -> {
            call.respondText("The sep24 interactive withdrawal has been successfully started.")

            val asset =
              token.data["asset"] ?: throw ClientException("Missing amountExpected.asset field")

            val stellarAsset = asset.replace("stellar:", "")

            // Run withdrawal processing asynchronously
            CoroutineScope(Dispatchers.Default).launch {
              withdrawalService.processWithdrawal(transactionId, amountExpected, stellarAsset)
            }
          }
          else ->
            call.respondText(
              "The only supported operations are \"deposit\" or \"withdrawal\"",
              status = HttpStatusCode.BadRequest,
            )
        }
      } catch (e: ClientException) {
        log.error { e }
        call.respondText(e.message!!, status = HttpStatusCode.BadRequest)
      } catch (e: Exception) {
        log.error { e }
        call.respondText(
          "Error occurred: ${e.message}",
          status = HttpStatusCode.InternalServerError,
        )
      }
    }
  }
}
