package org.stellar.reference.plugins

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.stellar.reference.ClientException
import org.stellar.reference.data.ErrorResponse
import org.stellar.reference.data.Success
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT
import org.stellar.reference.service.sep31.ReceiveService

private val log = KotlinLogging.logger {}

fun Route.testSep31(receiveService: ReceiveService) {

  authenticate(AUTH_CONFIG_ENDPOINT) {
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
            ErrorResponse("An internal error occurred"),
          )
        }
      }
    }
  }
}
