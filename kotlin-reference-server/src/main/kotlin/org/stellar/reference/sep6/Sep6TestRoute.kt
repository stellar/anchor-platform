package org.stellar.reference.plugins

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.stellar.reference.ClientException
import org.stellar.reference.data.ErrorResponse
import org.stellar.reference.data.Success
import org.stellar.reference.event.processor.Sep6EventProcessor

private val log = KotlinLogging.logger {}

fun Route.testSep6() {
  // Lets a test that drives a SEP-6 transaction through RPC calls itself (e.g. holding it at a
  // given status) opt that one transaction out of the processor's automatic advancement, instead
  // of disabling it for every transaction.
  route("/sep6/transactions/{transactionId}/skip-auto-advance") {
    post {
      try {
        val transactionId =
          call.parameters["transactionId"]
            ?: throw ClientException("Missing transactionId parameter")

        Sep6EventProcessor.skipAutoAdvance(transactionId)

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
