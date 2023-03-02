package com.example.plugins

import com.example.ClientException
import com.example.data.DepositRequest
import com.example.data.ErrorResponse
import com.example.data.Success
import com.example.data.WithdrawalRequest
import com.example.jwt.JwtDecoder
import com.example.sep24.DepositService
import com.example.sep24.Sep24Helper
import com.example.sep24.WithdrawalService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

fun Route.sep24(
  sep24: Sep24Helper,
  depositService: DepositService,
  withdrawalService: WithdrawalService
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

        val token = JwtDecoder.decode(header.replace(Regex.fromLiteral("Bearer\\s+"), ""))

        val transactionId = token.jti

        log.info("Starting /sep24/interactive with token $token")

        if (token.exp > System.currentTimeMillis()) {
          throw ClientException("Token expired")
        }

        // TODO: return new JWT here
        call.respond(Success(transactionId))
      } catch (e: ClientException) {
        call.respond(ErrorResponse(e.message!!))
      } catch (e: Exception) {
        call.respond(
          ErrorResponse("Error occurred: ${e.message}"),
        )
      }
    }
  }

  route("/kyc") {
    post {
      try {
        val header =
          call.request.headers["Authorization"]
            ?: throw ClientException("Missing Authorization header")

        if (!header.startsWith("Bearer")) {
          throw ClientException("Invalid Authorization header")
        }

        val sessionId = header.replace(Regex.fromLiteral("Bearer\\s+"), "")

        // TODO: decode sessionID
        val transaction = sep24.getTransaction(sessionId)

        when (transaction.kind.lowercase()) {
          "deposit" -> {
            val deposit = call.receive<DepositRequest>()

            log.info { "User requested a deposit: $deposit" }

            val account = transaction.toAccount ?: throw ClientException("Missing toAccount field")
            val assetCode =
              transaction.requestAssetCode
                ?: throw ClientException("Missing requestAssetCode field")
            val assetIssuer =
              transaction.requestAssetIssuer
                ?: throw ClientException("Missing requestAssetIssuer field")
            val memo = transaction.memo
            val memoType = transaction.memoType

            call.respond(Success(sessionId))

            // Run deposit processing asynchronously
            CoroutineScope(Job()).launch {
              depositService.processDeposit(
                transaction.id,
                deposit.amount.toBigDecimal(),
                account,
                assetCode,
                assetIssuer,
                memo,
                memoType
              )
            }
          }
          "withdrawal" -> {
            val withdrawal = call.receive<WithdrawalRequest>()

            call.respond(Success(sessionId))

            // Run deposit processing asynchronously
            CoroutineScope(Job()).launch {
              withdrawalService.processWithdrawal(transaction.id, withdrawal.amount.toBigDecimal())
            }
          }
          else ->
            call.respond(
              ErrorResponse("The only supported operations are \"deposit\" or \"withdrawal\""),
            )
        }
      } catch (e: ClientException) {
        call.respond(ErrorResponse(e.message!!))
      } catch (e: Exception) {
        call.respond(
          ErrorResponse("Error occurred: ${e.message}"),
        )
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

        val sessionId = header.replace(Regex.fromLiteral("Bearer\\s+"), "")

        // TODO: decode sessionID
        val transaction = sep24.getTransaction(sessionId)

        call.respond(transaction)
      } catch (e: ClientException) {
        call.respond(ErrorResponse(e.message!!))
      } catch (e: Exception) {
        call.respond(
          ErrorResponse("Error occurred: ${e.message}"),
        )
      }
    }
  }
}
