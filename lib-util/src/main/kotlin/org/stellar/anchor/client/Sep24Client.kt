package org.stellar.anchor.client

import org.stellar.anchor.api.sep.sep24.InfoResponse
import org.stellar.anchor.api.sep.sep24.InteractiveTransactionResponse
import org.stellar.anchor.api.sep.sep24.Sep24GetTransactionResponse

class Sep24Client(private val endpoint: String, private val jwt: String) : SepClient() {

  fun getInfo(): InfoResponse {
    val responseBody = httpGet("$endpoint/info", jwt)
    return gson.fromJson(responseBody, InfoResponse::class.java)
  }

  fun withdraw(requestData: Map<String, String>?): InteractiveTransactionResponse {
    val url = "$endpoint/transactions/withdraw/interactive"
    val responseBody = httpPost(url, requestData!!, jwt)
    return gson.fromJson(responseBody, InteractiveTransactionResponse::class.java)
  }

  fun deposit(requestData: Map<String, String>?): InteractiveTransactionResponse {
    val url = "$endpoint/transactions/deposit/interactive"
    val responseBody = httpPost(url, requestData!!, jwt)
    return gson.fromJson(responseBody, InteractiveTransactionResponse::class.java)
  }

  fun getTransaction(id: String, assetCode: String): Sep24GetTransactionResponse {
    val responseBody = httpGet("$endpoint/transaction?id=$id&asset_code=$assetCode", jwt)
    return gson.fromJson(responseBody, Sep24GetTransactionResponse::class.java)
  }
}
