package org.stellar.anchor.client

import com.google.gson.reflect.TypeToken
import java.security.PrivateKey
import org.stellar.anchor.api.custody.CreateCustodyTransactionRequest
import org.stellar.anchor.api.custody.GenerateDepositAddressResponse
import org.stellar.anchor.util.RSAUtil

class CustodyApiClient(
  private val endpoint: String,
  private val jwt: String,
  private val webhookPrivateKey: PrivateKey
) : SepClient() {

  fun generateDepositAddress(asset: String): GenerateDepositAddressResponse {
    val url = "$endpoint/assets/$asset/addresses"
    val responseBody = httpPost(url, mapOf<String, String>(), jwt)
    return gson.fromJson(responseBody, GenerateDepositAddressResponse::class.java)
  }

  fun createTransaction(custodyTransaction: CreateCustodyTransactionRequest) {
    val url = "$endpoint/transactions"
    val type = object : TypeToken<Map<String?, *>?>() {}.type
    val requestBody: Map<String, Any> = gson.fromJson(gson.toJson(custodyTransaction), type)
    httpPost(url, requestBody, jwt)
  }

  fun createTransactionPayment(transactionId: String) {
    val url = "$endpoint/transactions/$transactionId/payments"
    httpPost(url, mapOf<String, String>(), jwt)
  }

  fun sendWebhook(webhookRequest: String) {
    val webhookSignature = RSAUtil.sign(webhookRequest, webhookPrivateKey)
    httpPost("$endpoint/webhook", webhookRequest, mapOf("fireblocks-signature" to webhookSignature))
  }
}
