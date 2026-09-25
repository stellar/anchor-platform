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

  fun getPrices(sellAsset: String, sellAmount: String): GetPricesResponse {
    // build URL
    val urlBuilder =
      this.endpoint
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("prices")
        .addQueryParameter("sell_asset", sellAsset)
        .addQueryParameter("sell_amount", sellAmount)

    val responseBody = httpGet(urlBuilder.build().toString(), jwt)
    return gson.fromJson(responseBody, GetPricesResponse::class.java)
  }

  fun getPrice(
    sellAsset: String,
    sellAmount: String,
    buyAsset: String,
    context: Sep38Context
  ): GetPriceResponse {
    // build URL
    val urlBuilder =
      this.endpoint
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("price")
        .addQueryParameter("sell_asset", sellAsset)
        .addQueryParameter("sell_amount", sellAmount)
        .addQueryParameter("buy_asset", buyAsset)
        .addQueryParameter("context", context.toString())

    val responseBody = httpGet(urlBuilder.build().toString(), jwt)
    return gson.fromJson(responseBody, GetPriceResponse::class.java)
  }

  fun postQuote(
    sellAsset: String,
    sellAmount: String,
    buyAsset: String,
    context: Sep38Context = SEP31,
    expireAfter: Instant? = null,
    sellDeliveryMethod: String? = null,
    buyDeliveryMethod: String? = null,
  ): Sep38QuoteResponse {
    // build request body
    val requestBody =
      hashMapOf(
        "sell_asset" to sellAsset,
        "sell_amount" to sellAmount,
        "buy_asset" to buyAsset,
        "context" to context,
      )
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
