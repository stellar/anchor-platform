package org.stellar.anchor.platform

import org.stellar.anchor.api.sep.sep31.Sep31InfoResponse
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionResponse
import shadow.com.google.common.reflect.TypeToken

class Sep31Client(private val endpoint: String, private val jwt: String) : SepClient() {
  fun getInfo(): Sep31InfoResponse {
    println("$endpoint/info")
    val responseBody = httpGet("$endpoint/info", jwt)
    return gson.fromJson(responseBody, Sep31InfoResponse::class.java)
  }

  fun postTransaction(txnRequest: Sep31PostTransactionRequest): Sep31PostTransactionResponse {
    val url = "$endpoint/transactions"
    println("POST $url")

    val type = object : TypeToken<Map<String?, *>?>() {}.type
    val requestBody: Map<String, Any> = gson.fromJson(gson.toJson(txnRequest), type)

    val responseBody = httpPost(url, requestBody, jwt)
    return gson.fromJson(responseBody, Sep31PostTransactionResponse::class.java)
  }
}
