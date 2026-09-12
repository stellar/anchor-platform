package org.stellar.reference.plugins

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.stellar.reference.ClientException
import org.stellar.reference.data.ErrorResponse
import org.stellar.reference.data.Success
import org.stellar.reference.event.processor.Sep31EventProcessor
import org.stellar.reference.service.sep31.ReceiveService

private val log = KotlinLogging.logger {}

fun Route.testSep31(receiveService: ReceiveService) {

  route("/sep31/transactions/{transactionId}/process") {
    post {
      try {
        val transactionId =
          call.parameters["transactionId"]
            ?: throw ClientException("Missing transactionId parameter")

        // Run receive processing asynchronously
        CoroutineScope(Job()).launch { receiveService.processReceive(transactionId) }

        call.respond(Success(transactionId))
      } catch (e: ClientException) {
        log.error { e }
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message!!))
      } catch (e: Exception) {
        log.error { e }
        call.respond(
          HttpStatusCode.InternalServerError,
          ErrorResponse("Error occurred: ${e.message}"),
        )
      }
    }
  }

  // Lets a test that drives a SEP-31 transaction through RPC calls itself (e.g. exercising an
  // error/recovery path Sep31EventProcessor doesn't know about) opt that one transaction out of
  // the processor's automatic advancement, instead of disabling it for every transaction.
  route("/sep31/transactions/{transactionId}/skip-auto-advance") {
    post {
      try {
        val transactionId =
          call.parameters["transactionId"]
            ?: throw ClientException("Missing transactionId parameter")

        Sep31EventProcessor.skipAutoAdvance(transactionId)

        call.respond(Success(transactionId))
      } catch (e: ClientException) {
        log.error { e }
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message!!))
      } catch (e: Exception) {
        log.error { e }
        call.respond(
          HttpStatusCode.InternalServerError,
          ErrorResponse("Error occurred: ${e.message}"),
        )
      }
    }
  }
}
