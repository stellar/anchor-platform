package org.stellar.anchor.client

import java.time.Instant
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.stellar.anchor.api.sep.sep38.GetPriceResponse
import org.stellar.anchor.api.sep.sep38.GetPricesResponse
import org.stellar.anchor.api.sep.sep38.InfoResponse
import org.stellar.anchor.api.sep.sep38.Sep38Context
import org.stellar.anchor.api.sep.sep38.Sep38Context.*
import org.stellar.anchor.api.sep.sep38.Sep38QuoteResponse

class Sep38Client(private val endpoint: String, private val jwt: String) : SepClient() {
  fun getInfo(): InfoResponse {
    val responseBody = httpGet("$endpoint/info", jwt)
    return gson.fromJson(responseBody, InfoResponse::class.java)
  }

  fun getPrices(
    sellAsset: String,
    sellAmount: String,
    sellDeliveryMethod: String? = null,
    buyDeliveryMethod: String? = null,
  ): GetPricesResponse {
    // build URL
    val urlBuilder =
      this.endpoint
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("prices")
        .addQueryParameter("sell_asset", sellAsset)
        .addQueryParameter("sell_amount", sellAmount)
    sellDeliveryMethod?.let { urlBuilder.addQueryParameter("sell_delivery_method", it) }
    buyDeliveryMethod?.let { urlBuilder.addQueryParameter("buy_delivery_method", it) }

    val responseBody = httpGet(urlBuilder.build().toString(), jwt)
    return gson.fromJson(responseBody, GetPricesResponse::class.java)
  }

  fun getPrice(
    sellAsset: String,
    sellAmount: String?,
    buyAsset: String,
    context: Sep38Context,
    buyAmount: String? = null,
    sellDeliveryMethod: String? = null,
    buyDeliveryMethod: String? = null,
  ): GetPriceResponse {
    // build URL
    val urlBuilder =
      this.endpoint
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("price")
        .addQueryParameter("sell_asset", sellAsset)
        .addQueryParameter("buy_asset", buyAsset)
        .addQueryParameter("context", context.toString())
    sellAmount?.let { urlBuilder.addQueryParameter("sell_amount", it) }
    buyAmount?.let { urlBuilder.addQueryParameter("buy_amount", it) }
    sellDeliveryMethod?.let { urlBuilder.addQueryParameter("sell_delivery_method", it) }
    buyDeliveryMethod?.let { urlBuilder.addQueryParameter("buy_delivery_method", it) }

    val responseBody = httpGet(urlBuilder.build().toString(), jwt)
    return gson.fromJson(responseBody, GetPriceResponse::class.java)
  }

  fun postQuote(
    sellAsset: String,
    sellAmount: String?,
    buyAsset: String,
    context: Sep38Context = SEP31,
    expireAfter: Instant? = null,
    sellDeliveryMethod: String? = null,
    buyDeliveryMethod: String? = null,
    buyAmount: String? = null,
  ): Sep38QuoteResponse {
    // build request body
    val requestBody =
      hashMapOf(
        "sell_asset" to sellAsset,
        "buy_asset" to buyAsset,
        "context" to context,
      )
    sellAmount?.let { requestBody["sell_amount"] = it }
    buyAmount?.let { requestBody["buy_amount"] = it }
    if (expireAfter != null) {
      requestBody["expire_after"] = DateTimeFormatter.ISO_INSTANT.format(expireAfter)
    }
    if (sellDeliveryMethod != null) {
      requestBody["sell_delivery_method"] = sellDeliveryMethod
    }
    if (buyDeliveryMethod != null) {
      requestBody["buy_delivery_method"] = buyDeliveryMethod
    }

    val responseBody = httpPost("$endpoint/quote", requestBody, jwt)
    return gson.fromJson(responseBody, Sep38QuoteResponse::class.java)
  }

  fun getQuote(quoteId: String): Sep38QuoteResponse {
    // build URL
    val url = "$endpoint/quote/$quoteId"

    val responseBody = httpGet(url, jwt)
    return gson.fromJson(responseBody, Sep38QuoteResponse::class.java)
  }
}
