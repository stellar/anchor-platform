package org.stellar.anchor.platform

import org.stellar.anchor.api.sep.sep24.GetTransactionResponse
import org.stellar.anchor.api.sep.sep24.InfoResponse
import org.stellar.anchor.api.sep.sep24.InteractiveTransactionResponse

class Sep24Client(private val endpoint: String, private val jwt: String) : SepClient() {

  fun getInfo(): InfoResponse {
    println("SEP24 $endpoint/info")
    val responseBody = httpGet("$endpoint/info", jwt)
    return gson.fromJson(responseBody, InfoResponse::class.java)
  }

  fun withdraw(requestData: Map<String, String>?): InteractiveTransactionResponse {
    val url = "$endpoint/transactions/withdraw/interactive"
    println("SEP24 $url")
    val responseBody = httpPost(url, requestData!!, jwt)
    return gson.fromJson(responseBody, InteractiveTransactionResponse::class.java)
  }

  fun deposit(requestData: Map<String, String>?): InteractiveTransactionResponse {
    val url = "$endpoint/transactions/deposit/interactive"
    println("SEP24 $url")
    val responseBody = httpPost(url, requestData!!, jwt)
    return gson.fromJson(responseBody, InteractiveTransactionResponse::class.java)
  }

  fun getTransaction(id: String, assetCode: String): GetTransactionResponse {
    println("SEP24 $endpoint/transactions")
    val responseBody = httpGet("$endpoint/transaction?id=$id&asset_code=$assetCode", jwt)
    return gson.fromJson(responseBody, GetTransactionResponse::class.java)
  }
}
