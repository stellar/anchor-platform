package org.stellar.reference.sep24

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.stellar.reference.ClientException
import org.stellar.reference.UnauthorizedException
import org.stellar.reference.data.DepositRequest
import org.stellar.reference.data.ErrorResponse
import org.stellar.reference.data.Success
import org.stellar.reference.data.WithdrawalRequest
import org.stellar.reference.jwt.JwtDecoder
import org.stellar.reference.jwt.Sep24SessionToken
import org.stellar.reference.service.SepHelper

private val log = KotlinLogging.logger {}

private const val INTERACTIVE_AUDIENCE = "sep24_interactive"
private const val MORE_INFO_AUDIENCE = "sep24_more_info"
private const val INTERNAL_ERROR_MESSAGE = "An internal error occurred"

private fun ApplicationCall.bearerToken(): String {
  val header =
    request.headers["Authorization"] ?: throw ClientException("Missing Authorization header")
  if (!header.startsWith("Bearer")) {
    throw ClientException("Invalid Authorization header")
  }
  return header.replace(Regex("Bearer\\s+"), "")
}

private fun sessionTransactionId(sessionToken: String, jwtKey: String): String =
  try {
    Sep24SessionToken.verify(sessionToken, jwtKey)
  } catch (e: Exception) {
    throw UnauthorizedException("Invalid or expired session")
  }

private fun readableTransactionId(token: String, jwtKey: String, moreInfoJwtKey: String?): String {
  runCatching { Sep24SessionToken.verify(token, jwtKey) }
    .onSuccess {
      return it
    }
  if (moreInfoJwtKey != null) {
    runCatching { JwtDecoder.decode(token, moreInfoJwtKey, MORE_INFO_AUDIENCE).transactionId }
      .onSuccess {
        return it
      }
  }
  throw UnauthorizedException("Invalid or expired session")
}

private suspend fun ApplicationCall.respondUnauthorized(e: UnauthorizedException) {
  respond(HttpStatusCode.Unauthorized, ErrorResponse(e.message!!))
}

fun Route.sep24(
  sep24: SepHelper,
  depositService: DepositService,
  withdrawalService: WithdrawalService,
  jwtKey: String,
  moreInfoJwtKey: String? = null,
  sessionTtlSeconds: Long = Sep24SessionToken.DEFAULT_TTL_SECONDS,
) {
  route("/start") {
    post {
      try {
        val interactiveToken = call.bearerToken()

        val token =
          try {
            JwtDecoder.decode(interactiveToken, jwtKey, INTERACTIVE_AUDIENCE)
          } catch (e: Exception) {
            throw UnauthorizedException("Invalid or expired interactive token")
          }

        log.info { "Starting /sep24/interactive for transaction ${token.transactionId}" }

        call.respond(
          Success(Sep24SessionToken.issue(token.transactionId, jwtKey, sessionTtlSeconds))
        )
      } catch (e: UnauthorizedException) {
        log.error { e }
        call.respondUnauthorized(e)
      } catch (e: ClientException) {
        log.error { e }
        call.respond(ErrorResponse(e.message!!))
      } catch (e: Exception) {
        log.error { e }
        call.respond(ErrorResponse(INTERNAL_ERROR_MESSAGE))
      }
    }
  }

  // Submits user input and starts transaction processing flow
  route("/submit") {
    post {
      try {
        val sessionToken = call.bearerToken()
        val transactionId = sessionTransactionId(sessionToken, jwtKey)

        val transaction = sep24.getTransaction(transactionId)

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

            call.respond(Success(sessionToken))

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

            call.respond(Success(sessionToken))

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
      } catch (e: UnauthorizedException) {
        log.error { e }
        call.respondUnauthorized(e)
      } catch (e: ClientException) {
        log.error { e }
        call.respond(ErrorResponse(e.message!!))
      } catch (e: Exception) {
        log.error { e }
        call.respond(ErrorResponse(INTERNAL_ERROR_MESSAGE))
      }
    }
  }

  route("transaction") {
    get {
      try {
        val transactionId = readableTransactionId(call.bearerToken(), jwtKey, moreInfoJwtKey)

        val transaction = sep24.getTransaction(transactionId)

        call.respond(transaction)
      } catch (e: UnauthorizedException) {
        log.error { e }
        call.respondUnauthorized(e)
      } catch (e: ClientException) {
        log.error { e }
        call.respond(ErrorResponse(e.message!!))
      } catch (e: Exception) {
        log.error { e }
        call.respond(ErrorResponse(INTERNAL_ERROR_MESSAGE))
      }
    }
  }
}
