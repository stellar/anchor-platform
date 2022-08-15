package org.stellar.anchor.platform

import com.google.gson.reflect.TypeToken
import org.stellar.anchor.api.sep.sep31.Sep31GetTransactionResponse
import org.stellar.anchor.api.sep.sep31.Sep31InfoResponse
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionResponse

class Sep31Client(private val endpoint: String, private val jwt: String) : SepClient() {
  fun getInfo(): Sep31InfoResponse {
    println("$endpoint/info")
    val responseBody = httpGet("$endpoint/info", jwt)
    return gson.fromJson(responseBody, Sep31InfoResponse::class.java)
  }

  fun getTransaction(txId: String): Sep31GetTransactionResponse {
    // build URL
    val url = "$endpoint/transactions/$txId"
    println("GET $url")

    val responseBody = httpGet(url, jwt)
    return gson.fromJson(responseBody, Sep31GetTransactionResponse::class.java)
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
