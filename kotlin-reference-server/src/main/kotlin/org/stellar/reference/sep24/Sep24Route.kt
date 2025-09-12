package org.stellar.reference.sep24

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.stellar.reference.ClientException
import org.stellar.reference.data.DepositRequest
import org.stellar.reference.data.ErrorResponse
import org.stellar.reference.data.Success
import org.stellar.reference.data.WithdrawalRequest
import org.stellar.reference.jwt.JwtDecoder
import org.stellar.reference.service.SepHelper

private val log = KotlinLogging.logger {}

fun Route.sep24(
  sep24: SepHelper,
  depositService: DepositService,
  withdrawalService: WithdrawalService,
  jwtKey: String,
) {
  route("/start") {
    post {
      try {
        val header =
          call.request.headers["Authorization"]
            ?: throw ClientException("Missing Authorization header")

        if (!header.startsWith("Bearer")) {
          throw ClientException("Invalid Authorization header")
        }

        val token = JwtDecoder.decode(header.replace(Regex("Bearer\\s+"), ""), jwtKey)

        val transactionId = token.transactionId

        log.info { "Starting /sep24/interactive with token $token" }

        if (token.expiration > System.currentTimeMillis()) {
          throw ClientException("Token expired")
        }

        // TODO: return new JWT here
        call.respond(Success(transactionId))
      } catch (e: ClientException) {
        log.error { e }
        call.respond(ErrorResponse(e.message!!))
      } catch (e: Exception) {
        log.error { e }
        call.respond(ErrorResponse("Error occurred: ${e.message}"))
      }
    }
  }

  // Submits user input and starts transaction processing flow
  route("/submit") {
    post {
      try {
        val header =
          call.request.headers["Authorization"]
            ?: throw ClientException("Missing Authorization header")

        if (!header.startsWith("Bearer")) {
          throw ClientException("Invalid Authorization header")
        }

        val sessionId = header.replace(Regex("Bearer\\s+"), "")

        // TODO: decode sessionID
        val transaction = sep24.getTransaction(sessionId)

        if (transaction.status != "incomplete") {
          throw ClientException("Transaction has already been started.")
        }

        when (transaction.kind.lowercase()) {
          "deposit" -> {
            val deposit = call.receive<DepositRequest>()

            log.info { "User requested a deposit: $deposit" }

            val account =
              transaction.destinationAccount
                ?: throw ClientException("Missing destination_account field")
            val asset =
              transaction.amountExpected?.asset
                ?: throw ClientException("Missing amountExpected.asset field")
            val memo = transaction.memo

            call.respond(Success(sessionId))

            val stellarAsset = asset.replace("stellar:", "")

            // Run deposit processing asynchronously
            CoroutineScope(Job()).launch {
              depositService.processDeposit(
                transaction.id,
                deposit.amount.toBigDecimal(),
                account,
                stellarAsset,
                memo
              )
            }
          }
          "withdrawal" -> {
            val withdrawal = call.receive<WithdrawalRequest>()

            call.respond(Success(sessionId))

            val asset =
              transaction.amountExpected?.asset
                ?: throw ClientException("Missing amountExpected.asset field")

            val stellarAsset = asset.replace("stellar:", "")

            // Run deposit processing asynchronously
            CoroutineScope(Job()).launch {
              withdrawalService.processWithdrawal(
                transaction.id,
                withdrawal.amount.toBigDecimal(),
                stellarAsset,
              )
            }
          }
          else ->
            call.respond(
              ErrorResponse("The only supported operations are \"deposit\" or \"withdrawal\"")
            )
        }
      } catch (e: ClientException) {
        log.error { e }
        call.respond(ErrorResponse(e.message!!))
      } catch (e: Exception) {
        log.error { e }
        call.respond(ErrorResponse("Error occurred: ${e.message}"))
      }
    }
  }

  route("transaction") {
    get {
      try {
        val header =
          call.request.headers["Authorization"]
            ?: throw ClientException("Missing Authorization header")

        if (!header.startsWith("Bearer")) {
          throw ClientException("Invalid Authorization header")
        }

        val sessionId = header.replace(Regex("Bearer\\s+"), "")

        // TODO: decode sessionID
        val transaction = sep24.getTransaction(sessionId)

        call.respond(transaction)
      } catch (e: ClientException) {
        log.error { e }
        call.respond(ErrorResponse(e.message!!))
      } catch (e: Exception) {
        log.error { e }
        call.respond(ErrorResponse("Error occurred: ${e.message}"))
      }
    }
  }
}
