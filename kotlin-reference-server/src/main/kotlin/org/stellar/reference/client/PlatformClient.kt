package org.stellar.reference.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.*
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.GetTransactionsRequest
import org.stellar.anchor.api.platform.GetTransactionsResponse
import org.stellar.anchor.api.platform.PatchTransactionsRequest
import org.stellar.anchor.api.platform.PatchTransactionsResponse
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.util.GsonUtils
import org.stellar.reference.data.AuthSettings

class PlatformClient(
  private val httpClient: HttpClient,
  private val endpoint: String,
  private val authSettings: AuthSettings,
) {
  suspend fun getTransaction(id: String): GetTransactionResponse {
    val response =
      httpClient.request("$endpoint/transactions/$id") {
        method = HttpMethod.Get
        AuthHeaderUtil.addAuthHeaderIfNeeded(this, authSettings)
      }
    if (response.status != HttpStatusCode.OK) {
      throw Exception("Error getting transaction: ${response.status}")
    }
    return GsonUtils.getInstance()
      .fromJson(response.body<String>(), GetTransactionResponse::class.java)
  }

  suspend fun patchTransactions(request: PatchTransactionsRequest): PatchTransactionsResponse {
    val response =
      httpClient.request("$endpoint/transactions") {
        method = HttpMethod.Patch
        setBody(GsonUtils.getInstance().toJson(request))
        contentType(ContentType.Application.Json)
        AuthHeaderUtil.addAuthHeaderIfNeeded(this, authSettings)
      }
    if (response.status != HttpStatusCode.OK) {
      throw Exception("Error patching transaction: ${response.status}")
    }
    return GsonUtils.getInstance()
      .fromJson(response.body<String>(), PatchTransactionsResponse::class.java)
  }

  suspend fun getTransactions(request: GetTransactionsRequest): GetTransactionsResponse {
    val response =
      httpClient.request("$endpoint/transactions") {
        method = HttpMethod.Get
        AuthHeaderUtil.addAuthHeaderIfNeeded(this, authSettings)
        url {
          parameters.append("sep", request.sep.name.toLowerCasePreservingASCIIRules())
          if (request.orderBy != null) {
            parameters.append("order_by", request.orderBy.name.toLowerCasePreservingASCIIRules())
          }
          if (request.order != null) {
            parameters.append("order", request.order.name.toLowerCasePreservingASCIIRules())
          }
          if (request.statuses != null) {
            parameters.append("statuses", SepTransactionStatus.mergeStatusesList(request.statuses))
          }
          if (request.pageSize != null) {
            parameters.append("page_size", request.pageSize.toString())
          }
          if (request.pageNumber != null) {
            parameters.append("page_number", request.pageNumber.toString())
          }
        }
      }
    if (response.status != HttpStatusCode.OK) {
      throw Exception("Error getting transactions: ${response.status}")
    }
    return GsonUtils.getInstance()
      .fromJson(response.body<String>(), GetTransactionsResponse::class.java)
  }
}
