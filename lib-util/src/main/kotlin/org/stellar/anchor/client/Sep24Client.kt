package org.stellar.anchor.client

import com.google.gson.JsonParser
import okhttp3.MultipartBody
import okhttp3.Request
import org.stellar.anchor.api.sep.sep24.DepositTransactionResponse
import org.stellar.anchor.api.sep.sep24.InfoResponse
import org.stellar.anchor.api.sep.sep24.InteractiveTransactionResponse
import org.stellar.anchor.api.sep.sep24.Sep24GetTransactionResponse
import org.stellar.anchor.api.sep.sep24.WithdrawTransactionResponse

class Sep24Client(private val endpoint: String, private val jwt: String?) : SepClient() {
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

  /** Same as [deposit], but posts the fields as `multipart/form-data` instead of JSON. */
  fun depositMultipart(requestData: Map<String, String>): InteractiveTransactionResponse =
    postMultipart("$endpoint/transactions/deposit/interactive", requestData)

  /** Same as [withdraw], but posts the fields as `multipart/form-data` instead of JSON. */
  fun withdrawMultipart(requestData: Map<String, String>): InteractiveTransactionResponse =
    postMultipart("$endpoint/transactions/withdraw/interactive", requestData)

  private fun postMultipart(
    url: String,
    requestData: Map<String, String>,
  ): InteractiveTransactionResponse {
    val body =
      MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .apply { requestData.forEach { (key, value) -> addFormDataPart(key, value) } }
        .build()
    val request =
      Request.Builder()
        .url(url)
        .apply { if (jwt != null) header("Authorization", "Bearer $jwt") }
        .post(body)
        .build()
    val responseBody = handleResponse(client.newCall(request).execute())
    return gson.fromJson(responseBody, InteractiveTransactionResponse::class.java)
  }

  fun getTransaction(id: String, assetCode: String): Sep24GetTransactionResponse {
    val responseBody = httpGet("$endpoint/transaction?id=$id&asset_code=$assetCode", jwt)
    val root = JsonParser.parseString(responseBody).asJsonObject
    val txnJson = root.getAsJsonObject("transaction")
    val transaction =
      if (txnJson.get("kind").asString == "withdrawal") {
        gson.fromJson(txnJson, WithdrawTransactionResponse::class.java)
      } else {
        gson.fromJson(txnJson, DepositTransactionResponse::class.java)
      }
    return Sep24GetTransactionResponse().apply { this.transaction = transaction }
  }
}
