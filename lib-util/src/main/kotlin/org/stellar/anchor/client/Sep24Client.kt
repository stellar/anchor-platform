package org.stellar.anchor.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import org.stellar.anchor.api.sep.sep24.DepositTransactionResponse
import org.stellar.anchor.api.sep.sep24.GetTransactionsResponse
import org.stellar.anchor.api.sep.sep24.InfoResponse
import org.stellar.anchor.api.sep.sep24.InteractiveTransactionResponse
import org.stellar.anchor.api.sep.sep24.Sep24GetTransactionResponse
import org.stellar.anchor.api.sep.sep24.TransactionResponse
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
    return parseTransaction(responseBody)
  }

  /** Sends each entry of [request] verbatim as a query parameter of `GET /transaction`. */
  fun getTransaction(request: Map<String, String>): Sep24GetTransactionResponse {
    val urlBuilder = "$endpoint/transaction".toHttpUrl().newBuilder()
    request.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }

    return parseTransaction(httpGet(urlBuilder.build().toString(), jwt))
  }

  /** Sends each entry of [request] verbatim as a query parameter of `GET /transactions`. */
  fun getTransactions(request: Map<String, String>): GetTransactionsResponse {
    val urlBuilder = "$endpoint/transactions".toHttpUrl().newBuilder()
    request.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }

    val root = JsonParser.parseString(httpGet(urlBuilder.build().toString(), jwt)).asJsonObject
    return GetTransactionsResponse().apply {
      transactions =
        root.getAsJsonArray("transactions").map { parseTransactionObject(it.asJsonObject) }
    }
  }

  private fun parseTransaction(responseBody: String?): Sep24GetTransactionResponse {
    val root = JsonParser.parseString(responseBody).asJsonObject
    val transaction = parseTransactionObject(root.getAsJsonObject("transaction"))
    return Sep24GetTransactionResponse().apply { this.transaction = transaction }
  }

  private fun parseTransactionObject(txnJson: JsonObject): TransactionResponse =
    if (txnJson.get("kind").asString == "withdrawal") {
      gson.fromJson(txnJson, WithdrawTransactionResponse::class.java)
    } else {
      gson.fromJson(txnJson, DepositTransactionResponse::class.java)
    }
}
