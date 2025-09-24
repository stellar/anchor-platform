package org.stellar.reference.callbacks.customer

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import org.stellar.anchor.api.callback.GetCustomerRequest
import org.stellar.anchor.api.callback.PutCustomerRequest
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.callbacks.BadRequestException
import org.stellar.reference.di.AUTH_CONFIG_ENDPOINT

/**
 * Defines the routes related to the customer callback API. See
 * [Customer Callbacks](https://developers.stellar.org/api/anchor-platform/callbacks/customer/).
 *
 * @param customerService the [CustomerService] to use to process the requests.
 */
fun Route.customer(customerService: CustomerService) {
  authenticate(AUTH_CONFIG_ENDPOINT) {
    route("/customer") {
      get {
        val request =
          GetCustomerRequest.builder()
            .id(call.parameters["id"])
            .account(call.parameters["account"])
            .memo(call.parameters["memo"])
            .memoType(call.parameters["memo_type"])
            .transactionId(call.parameters["transaction_id"])
            .type(call.parameters["type"])
            .lang(call.parameters["lang"])
            .build()
        val response = GsonUtils.getInstance().toJson(customerService.getCustomer(request))
        call.respond(response)
      }
      put {
        val contentType = call.request.contentType()
        when {
          contentType.match(ContentType.Application.Json) -> {
            val request =
              GsonUtils.getInstance()
                .fromJson(call.receive<String>(), PutCustomerRequest::class.java)
            val response = GsonUtils.getInstance().toJson(customerService.upsertCustomer(request))
            call.respond(response)
          }
          contentType.match(ContentType.MultiPart.FormData) -> {
            val parts = call.receiveMultipart()
            val data = mutableMapOf<String, Any?>()

            parts.forEachPart { part ->
              when (part) {
                is PartData.FormItem -> {
                  data[part.name!!] = part.value
                }
                is PartData.FileItem -> {
                  val bytes = part.streamProvider().readBytes()
                  data[part.name!!] = bytes
                }
                is PartData.BinaryItem -> {
                  val bytes = part.provider().readBytes()
                  data[part.name!!] = bytes
                }
                else -> {}
              }
              part.dispose()
            }
            val json = GsonUtils.getInstance().toJson(data)
            val request = GsonUtils.getInstance().fromJson(json, PutCustomerRequest::class.java)
            val response = GsonUtils.getInstance().toJson(customerService.upsertCustomer(request))
            call.respond(response)
          }
          else ->
            throw BadRequestException(
              "Content-Type must be application/json or multipart/form-data but was $contentType"
            )
        }
      }
      delete("{id}") {
        try {
          val id = call.parameters["id"]!!
          customerService.deleteCustomer(id)
          call.respond(HttpStatusCode.NoContent)
        } catch (e: NullPointerException) {
          throw BadRequestException("id must be provided")
        }
      }
    }
  }
}
