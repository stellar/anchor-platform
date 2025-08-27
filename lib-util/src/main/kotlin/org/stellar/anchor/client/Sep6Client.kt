package org.stellar.anchor.client

import org.stellar.anchor.api.sep.sep6.InfoResponse
import org.stellar.anchor.api.sep.sep6.Sep6GetTransactionResponse
import org.stellar.anchor.api.sep.sep6.StartDepositResponse
import org.stellar.anchor.api.sep.sep6.StartWithdrawResponse

class Sep6Client(private val endpoint: String, var jwt: String) : SepClient() {
  fun getInfo(): InfoResponse {
    val responseBody = httpGet("$endpoint/info")
    return gson.fromJson(responseBody, InfoResponse::class.java)
  }

  fun deposit(request: Map<String, String>, exchange: Boolean = false): StartDepositResponse {
    val baseUrl = if (exchange) "$endpoint/deposit-exchange?" else "$endpoint/deposit?"
    val url = request.entries.fold(baseUrl) { acc, entry -> "$acc${entry.key}=${entry.value}&" }

    val responseBody = httpGet(url, jwt)
    return gson.fromJson(responseBody, StartDepositResponse::class.java)
  }

  fun withdraw(request: Map<String, String>, exchange: Boolean = false): StartWithdrawResponse {
    val baseUrl = if (exchange) "$endpoint/withdraw-exchange?" else "$endpoint/withdraw?"
    val url = request.entries.fold(baseUrl) { acc, entry -> "$acc${entry.key}=${entry.value}&" }

    val responseBody = httpGet(url, jwt)
    return gson.fromJson(responseBody, StartWithdrawResponse::class.java)
  }

  fun getTransaction(request: Map<String, String>): Sep6GetTransactionResponse {
    val baseUrl = "$endpoint/transaction?"
    val url = request.entries.fold(baseUrl) { acc, entry -> "$acc${entry.key}=${entry.value}&" }

    val responseBody = httpGet(url, jwt)
    return gson.fromJson(responseBody, Sep6GetTransactionResponse::class.java)
  }
}
