package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonParser
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest
import org.stellar.anchor.api.sep.sep38.Sep38Context.SEP31
import org.stellar.anchor.api.sep.sep38.Sep38Context.SEP6
import org.stellar.anchor.client.Sep12Client
import org.stellar.anchor.client.Sep24Client
import org.stellar.anchor.client.Sep31Client
import org.stellar.anchor.client.Sep38Client
import org.stellar.anchor.client.Sep6Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer1Json
import org.stellar.anchor.platform.integrationtest.Sep12Tests.Companion.testCustomer2Json
import org.stellar.anchor.platform.printRequest
import org.stellar.anchor.platform.printResponse
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

@Order(RUN_AFTER_PLATFORM_API_TESTS)
class Sep38Tests : IntegrationTestBase(TestConfig()) {
  private val sep38Client: Sep38Client =
    Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), this.token.token)
  private val sep31Client: Sep31Client =
    Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), this.token.token)
  private val sep24Client: Sep24Client =
    Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), this.token.token)
  private val sep6Client: Sep6Client =
    Sep6Client(toml.getString("TRANSFER_SERVER"), this.token.token)
  private val sep12Client: Sep12Client = Sep12Client(toml.getString("KYC_SERVER"), this.token.token)
  private val clientWalletAccount = KeyPair.fromSecretSeed(CLIENT_WALLET_SECRET).accountId
  private val gson = GsonUtils.getInstance()

  @Test
  fun `test sep38 info, price and prices endpoints`() {
    // GET {SEP38}/info
    printRequest("Calling GET /info")
    val info = sep38Client.getInfo()
    printResponse(info)

    // GET {SEP38}/prices
    printRequest("Calling GET /prices")
    val prices = sep38Client.getPrices("iso4217:USD", "100")
    printResponse(prices)

    // GET {SEP38}/price
    printRequest("Calling GET /price")
    val price =
      sep38Client.getPrice(
        "iso4217:USD",
        "100",
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        SEP31,
      )
    assertEquals(price.sellAmount, "100")

    // GET {SEP38}/price - native asset
    printRequest("Calling GET /price for native asset")
    val nativePrice =
      sep38Client.getPrice(
        "iso4217:USD",
        "10",
        "stellar:native",
        SEP6,
      )
    assertEquals(nativePrice.sellAmount, "10")

    // POST {SEP38}/quote
    printRequest("Calling POST /quote")
    var postQuote =
      sep38Client.postQuote(
        "iso4217:USD",
        "100",
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        SEP31,
      )
    assertEquals(postQuote.sellAmount, "100")

    // POST {SEP38}/quote with `expires_after`
    printRequest("Calling POST /quote")
    val expireAfter = Instant.now().plus(1, ChronoUnit.HOURS)
    postQuote =
      sep38Client.postQuote(
        "iso4217:USD",
        "100",
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        SEP31,
        expireAfter = expireAfter,
      )
    assertEquals(postQuote.sellAmount, "100")
    assertEquals(postQuote.sellAsset, "iso4217:USD")
    assertEquals(
      postQuote.buyAsset,
      "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    )

    // GET {SEP38}/quote/{id}
    printRequest("Calling GET /quote")
    val getQuote = sep38Client.getQuote(postQuote.id)
    printResponse(getQuote)
    assertEquals(postQuote, getQuote)
  }

  @Test
  fun `test quote cannot be reused across SEPs`() {
    // Register SEP-12 customers needed for SEP-31
    val sender =
      sep12Client.putCustomer(
        gson.fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
      )!!
    val receiver =
      sep12Client.putCustomer(
        gson.fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
      )!!

    // Create a firm quote
    val quote =
      sep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )

    // First use: SEP-31 transaction — must succeed and consume the quote
    val txnRequest =
      Sep31PostTransactionRequest().apply {
        amount = "10"
        assetCode = "USDC"
        assetIssuer = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        senderId = sender.id
        receiverId = receiver.id
        quoteId = quote.id
        fundingMethod = "SEPA"
        fields =
          org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest.Sep31TxnFields(
            hashMapOf(
              "receiver_routing_number" to "r0123",
              "receiver_account_number" to "a0456",
              "type" to "SWIFT",
            )
          )
      }
    sep31Client.postTransaction(txnRequest)

    // Second use: SEP-31 again with the same quote — must fail
    val ex31 = assertThrows<SepException> { sep31Client.postTransaction(txnRequest) }
    assert(ex31.message!!.contains("has already been used")) {
      "Expected 'has already been used' but got: ${ex31.message}"
    }

    // Cross-SEP: SEP-6 deposit-exchange with the consumed quote — must fail
    val ex6 =
      assertThrows<SepException> {
        sep6Client.deposit(
          mapOf(
            "destination_asset" to "USDC",
            "source_asset" to "iso4217:USD",
            "amount" to "10",
            "account" to clientWalletAccount,
            "type" to "SWIFT",
            "quote_id" to quote.id,
          ),
          exchange = true,
        )
      }
    assert(ex6.message!!.contains("has already been used")) {
      "Expected 'has already been used' but got: ${ex6.message}"
    }

    // Cross-SEP: SEP-24 withdraw with the consumed quote — must fail
    val ex24 =
      assertThrows<SepException> {
        sep24Client.withdraw(
          mapOf(
            "asset_code" to "USDC",
            "amount" to "10",
            "quote_id" to quote.id,
          )
        )
      }
    assert(ex24.message!!.contains("has already been used")) {
      "Expected 'has already been used' but got: ${ex24.message}"
    }
  }

  @Test
  fun `test concurrent POST sep31 transactions with same quote_id result in exactly one success`() {
    val sender =
      sep12Client.putCustomer(
        gson.fromJson(testCustomer1Json, Sep12PutCustomerRequest::class.java)
      )!!
    val receiver =
      sep12Client.putCustomer(
        gson.fromJson(testCustomer2Json, Sep12PutCustomerRequest::class.java)
      )!!

    val quote =
      sep38Client.postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "stellar:JPYC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )

    val txnRequest =
      Sep31PostTransactionRequest().apply {
        amount = "10"
        assetCode = "USDC"
        assetIssuer = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        senderId = sender.id
        receiverId = receiver.id
        quoteId = quote.id
        fundingMethod = "SEPA"
        fields =
          org.stellar.anchor.api.sep.sep31.Sep31PostTransactionRequest.Sep31TxnFields(
            hashMapOf(
              "receiver_routing_number" to "r0123",
              "receiver_account_number" to "a0456",
              "type" to "SWIFT",
            )
          )
      }

    val executor = Executors.newFixedThreadPool(2)
    val futures =
      (1..2).map {
        executor.submit<Exception?> {
          try {
            sep31Client.postTransaction(txnRequest)
            null
          } catch (e: SepException) {
            e
          }
        }
      }

    val exceptions = futures.map { it.get() }
    executor.shutdown()

    val successCount = exceptions.count { it == null }
    val failureCount = exceptions.count { it != null }

    assertEquals(1, successCount, "Expected exactly 1 successful transaction, got $successCount")
    assertEquals(1, failureCount, "Expected exactly 1 failed transaction, got $failureCount")

    val error = exceptions.first { it != null }!!
    assert(error.message!!.contains("has already been used")) {
      "Expected 'has already been used' but got: ${error.message}"
    }
  }

  // --- raw HTTP: exact status and body, with or without a token ---

  private val http = OkHttpClient()
  private val quoteServer = toml.getString("ANCHOR_QUOTE_SERVER")

  private fun raw(
    method: String,
    path: String,
    authorization: String? = null,
    json: String? = null,
  ): Pair<Int, String> {
    val builder = Request.Builder().url("$quoteServer$path")
    if (authorization != null) builder.header("Authorization", authorization)
    if (method == "POST") builder.post(json!!.toRequestBody("application/json".toMediaType()))
    else builder.get()
    http.newCall(builder.build()).execute().use { response ->
      return Pair(response.code, response.body?.string() ?: "")
    }
  }

  private val usdcAsset = "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  private val usdAsset = "iso4217:USD"
  private val unknownQuoteId = "00000000-0000-4000-8000-000000000000"
  private val quoteBody =
    """{"sell_asset":"$usdAsset","sell_amount":"100","buy_asset":"$usdcAsset","context":"sep31"}"""

  private fun assertForbidden(result: Pair<Int, String>) {
    assertEquals(403, result.first, "answered ${result.first}: ${result.second}")
    assertEquals(
      JsonParser.parseString("""{"error":"forbidden"}"""),
      JsonParser.parseString(result.second)
    )
  }

  // SEP38AU-01
  @Test
  fun `test GET quote without an Authorization header is forbidden`() {
    assertForbidden(raw("GET", "/quote/$unknownQuoteId"))
    // the same request with the class JWT is authenticated: an unknown id is a 404, not a 403
    assertEquals(404, raw("GET", "/quote/$unknownQuoteId", "Bearer ${this.token.token}").first)
  }

  // SEP38AU-02
  @Test
  fun `test GET quote with a bearer token that is not a JWT is forbidden`() {
    assertForbidden(raw("GET", "/quote/$unknownQuoteId", "Bearer not-a-jwt"))
  }

  // SEP38AU-03
  @Test
  fun `test POST quote without an Authorization header is forbidden`() {
    assertForbidden(raw("POST", "/quote", json = quoteBody))
    // the same request with the class JWT creates a quote
    assertEquals(201, raw("POST", "/quote", "Bearer ${this.token.token}", quoteBody).first)
  }

  // SEP38AU-04
  @Test
  fun `test POST quote with a bearer token that is not a JWT is forbidden`() {
    assertForbidden(raw("POST", "/quote", "Bearer not-a-jwt", quoteBody))
  }

  // SEP38AU-05: sep38.auth_enforced defaults to false, so these three need no token.
  @Test
  fun `test info, prices and price answer 200 without an Authorization header`() {
    assertEquals(200, raw("GET", "/info").first)
    assertEquals(200, raw("GET", "/prices?sell_asset=$usdAsset&sell_amount=100").first)
    assertEquals(
      200,
      raw(
          "GET",
          "/price?sell_asset=$usdAsset&sell_amount=100&buy_asset=$usdcAsset&context=sep31",
        )
        .first,
    )
  }

  // --- amounts: hand-computed values and the SEP-38 price formulas ---
  // The reference rate callback prices USD -> USDC at 1.02 and USDC -> USD at 1.05, with a fee of
  // 1.00 in the sell asset (`RateService.kt`).

  private fun dec(value: String?) = java.math.BigDecimal(value!!)

  private fun assertDec(expected: String, actual: String?, what: String) =
    assertEquals(
      0,
      dec(expected).compareTo(dec(actual)),
      "$what: expected $expected but was $actual",
    )

  /** T = unit * 1e-4 + buy_amount * 1e-10: the quantization of the reference rate callback. */
  private fun tolerance(unitPrice: String?, buyAmount: String?) =
    dec(unitPrice)
      .multiply(java.math.BigDecimal("0.0001"))
      .add(dec(buyAmount).multiply(java.math.BigDecimal("0.0000000001")))

  private fun assertWithin(
    tolerance: java.math.BigDecimal,
    left: java.math.BigDecimal,
    right: java.math.BigDecimal,
    what: String
  ) =
    assert(left.subtract(right).abs() <= tolerance) { "$what: |$left - $right| exceeds $tolerance" }

  private fun assertSepFormulas(response: org.stellar.anchor.api.sep.sep38.GetPriceResponse) {
    val sell = dec(response.sellAmount)
    val buy = dec(response.buyAmount)
    val fee = dec(response.fee.total)
    // SEP38PM-03: sell_amount = total_price * buy_amount
    assertWithin(
      tolerance(response.totalPrice, response.buyAmount),
      sell,
      dec(response.totalPrice).multiply(buy),
      "sell_amount vs total_price * buy_amount",
    )
    // SEP38PM-04, the fee being in the sell asset: sell_amount - fee = price * buy_amount
    assertWithin(
      tolerance(response.price, response.buyAmount),
      sell.subtract(fee),
      dec(response.price).multiply(buy),
      "sell_amount - fee vs price * buy_amount",
    )
  }

  // SEP38PM-01
  @Test
  fun `test GET price by sell_amount matches the hand-computed amounts`() {
    val price = sep38Client.getPrice(usdAsset, "100", usdcAsset, SEP31)

    assertDec("100", price.sellAmount, "sell_amount")
    assertDec("97.0588", price.buyAmount, "buy_amount") // (100 - 1.00) / 1.02
    assertDec("1.00", price.fee.total, "fee.total")
    assertEquals(usdAsset, price.fee.asset)
  }

  // SEP38PM-02
  @Test
  fun `test GET price by buy_amount matches the hand-computed amounts`() {
    val price = sep38Client.getPrice(usdAsset, null, usdcAsset, SEP31, buyAmount = "100")

    assertDec("100", price.buyAmount, "buy_amount")
    assertDec("103", price.sellAmount, "sell_amount") // 100 * 1.02 + 1.00
    assertDec("1.02", price.price, "price")
    assertDec("1.03", price.totalPrice, "total_price") // 103 / 100
    assertDec("1.00", price.fee.total, "fee.total")
  }

  // SEP38PM-03, SEP38PM-04
  @Test
  fun `test GET price satisfies the SEP-38 formulas in both directions and by both amounts`() {
    listOf(
        Pair(usdAsset, usdcAsset),
        Pair(usdcAsset, usdAsset),
      )
      .forEach { (sell, buy) ->
        assertSepFormulas(sep38Client.getPrice(sell, "100", buy, SEP31))
        assertSepFormulas(sep38Client.getPrice(sell, null, buy, SEP31, buyAmount = "100"))
      }
  }

  // --- quotes and prices agree ---

  private fun assertSameAmounts(
    expected: org.stellar.anchor.api.sep.sep38.GetPriceResponse,
    actual: org.stellar.anchor.api.sep.sep38.Sep38QuoteResponse,
  ) {
    assertDec(expected.sellAmount!!, actual.sellAmount, "sell_amount")
    assertDec(expected.buyAmount!!, actual.buyAmount, "buy_amount")
    assertDec(expected.price!!, actual.price, "price")
    assertDec(expected.totalPrice!!, actual.totalPrice, "total_price")
    assertDec(expected.fee.total, actual.fee.total, "fee.total")
    assertEquals(expected.fee.asset, actual.fee.asset)
  }

  private fun assertFeeDetailsSumToTotal(fee: org.stellar.anchor.api.shared.FeeDetails) {
    val sum =
      (fee.details ?: emptyList())
        .map { dec(it.amount) }
        .fold(java.math.BigDecimal.ZERO) { acc, amount -> acc.add(amount) }
    assertDec(sum.toPlainString(), fee.total, "fee.details[] sum vs fee.total")
  }

  // SEP38PM-06
  @Test
  fun `test POST quote by sell_amount agrees with GET price for the same input`() {
    val price = sep38Client.getPrice(usdAsset, "100", usdcAsset, SEP31)
    val quote = sep38Client.postQuote(usdAsset, "100", usdcAsset, SEP31)

    assertSameAmounts(price, quote)
  }

  // SEP38PM-07
  @Test
  fun `test POST quote by buy_amount matches the hand-computed amounts and the formulas`() {
    val quote = sep38Client.postQuote(usdAsset, null, usdcAsset, SEP31, buyAmount = "100")

    assertDec("100", quote.buyAmount, "buy_amount")
    assertDec("103", quote.sellAmount, "sell_amount")
    assertDec("1.02", quote.price, "price")
    assertDec("1.03", quote.totalPrice, "total_price")
    assertSepFormulas(
      org.stellar.anchor.api.sep.sep38.GetPriceResponse.builder()
        .price(quote.price)
        .totalPrice(quote.totalPrice)
        .sellAmount(quote.sellAmount)
        .buyAmount(quote.buyAmount)
        .fee(quote.fee)
        .build()
    )
  }

  // SEP38PM-05
  @Test
  fun `test fee details sum to the fee total on GET price, POST quote and GET quote`() {
    val price = sep38Client.getPrice(usdAsset, "100", usdcAsset, SEP31)
    val quote = sep38Client.postQuote(usdAsset, "100", usdcAsset, SEP31)
    val fetched = sep38Client.getQuote(quote.id)

    assertFeeDetailsSumToTotal(price.fee)
    assertFeeDetailsSumToTotal(quote.fee)
    assertFeeDetailsSumToTotal(fetched.fee)
    assertEquals(1, quote.fee.details.size) // the "Sell fee" of 1.00, so the sum is not vacuous
  }

  // SEP38PM-08
  @Test
  fun `test GET prices lists the exchangeable assets and agrees with GET price`() {
    val prices = sep38Client.getPrices(usdAsset, "100")

    val wantDecimals =
      mapOf(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP" to 7,
        "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5" to 4,
        "stellar:native" to 7,
      )
    assertEquals(wantDecimals.keys, prices.buyAssets.map { it.asset }.toSet())
    assertEquals(3, prices.buyAssets.size)
    prices.buyAssets.forEach { entry ->
      assertEquals(wantDecimals[entry.asset], entry.decimals, "decimals of ${entry.asset}")
      val price = sep38Client.getPrice(usdAsset, "100", entry.asset, SEP31)
      assertDec(price.price!!, entry.price, "price of ${entry.asset}")
    }
  }

  // --- shape of the responses, read from the raw JSON so a missing field fails ---

  private fun jsonObject(body: String) = JsonParser.parseString(body).asJsonObject

  private fun walletJwt() = "Bearer ${this.token.token}"

  private fun postQuoteRaw(json: String = quoteBody): Pair<Int, String> =
    raw("POST", "/quote", walletJwt(), json)

  private fun newIdentityJwt() =
    "Bearer " + runBlocking { anchor.auth().authenticate(SigningKeyPair(KeyPair.random())) }.token

  // SEP38RS-01
  @Test
  fun `test GET info lists the fiat assets with their country codes and delivery methods`() {
    val (status, body) = raw("GET", "/info")
    assertEquals(200, status)
    val assets =
      jsonObject(body)
        .getAsJsonArray("assets")
        .map { it.asJsonObject }
        .associateBy { it.get("asset").asString }

    fun methods(asset: String, key: String) =
      assets
        .getValue(asset)
        .getAsJsonArray(key)
        .map { it.asJsonObject }
        .map { Pair(it.get("name").asString, it.get("description").asString) }

    assertEquals(
      listOf("US"),
      assets.getValue(usdAsset).getAsJsonArray("country_codes").map { it.asString }
    )
    assertEquals(
      listOf(Pair("WIRE", "Send USD directly to the Anchor's bank account.")),
      methods(usdAsset, "sell_delivery_methods")
    )
    assertEquals(
      listOf(Pair("WIRE", "Have USD sent directly to your bank account.")),
      methods(usdAsset, "buy_delivery_methods")
    )
    assertEquals(
      listOf("CA"),
      assets.getValue("iso4217:CAD").getAsJsonArray("country_codes").map { it.asString }
    )
    assertEquals(
      listOf(Pair("WIRE", "Send CAD directly to the Anchor's bank account.")),
      methods("iso4217:CAD", "sell_delivery_methods")
    )
    assertEquals(
      listOf(Pair("WIRE", "Have CAD sent directly to your bank account.")),
      methods("iso4217:CAD", "buy_delivery_methods")
    )
    val native = assets.getValue("stellar:native")
    listOf("country_codes", "sell_delivery_methods", "buy_delivery_methods").forEach {
      assertEquals(false, native.has(it), "stellar:native must not carry $it")
    }
  }

  // SEP38RS-02, SEP38RS-03, SEP38DM-03
  @Test
  fun `test POST quote answers 201 with every SEP-38 field and no delivery method when none was sent`() {
    val before = Instant.now()
    val (status, body) = postQuoteRaw()
    assertEquals(201, status, body)
    val quote = jsonObject(body)

    java.util.UUID.fromString(quote.get("id").asString)
    assert(Instant.parse(quote.get("expires_at").asString).isAfter(before)) {
      "expires_at must be in the future: $body"
    }
    listOf("total_price", "price", "sell_amount", "buy_amount").forEach {
      dec(quote.get(it).asString)
    }
    assertEquals(usdAsset, quote.get("sell_asset").asString)
    assertEquals(usdcAsset, quote.get("buy_asset").asString)
    val fee = quote.getAsJsonObject("fee")
    dec(fee.get("total").asString)
    assertEquals(usdAsset, fee.get("asset").asString)
    assertEquals(true, fee.get("details").isJsonArray)
    listOf("sell_delivery_method", "buy_delivery_method").forEach {
      assertEquals(false, quote.has(it), "$it must be absent when it was not sent")
    }
  }

  // SEP38RS-03, SEP38DM-04
  @Test
  fun `test POST quote echoes the delivery method it was sent`() {
    val withMethod =
      """{"sell_asset":"$usdAsset","sell_amount":"100","sell_delivery_method":"WIRE","buy_asset":"$usdcAsset","context":"sep31"}"""
    val (status, body) = postQuoteRaw(withMethod)
    assertEquals(201, status, body)
    assertEquals("WIRE", jsonObject(body).get("sell_delivery_method").asString)
    assertEquals(false, jsonObject(body).has("buy_delivery_method"))
  }

  // SEP38RS-04
  @Test
  fun `test GET quote returns the body POST quote returned`() {
    val created = postQuoteRaw()
    assertEquals(201, created.first, created.second)
    val id = jsonObject(created.second).get("id").asString

    val (status, body) = raw("GET", "/quote/$id", walletJwt())

    assertEquals(200, status, body)
    assertEquals(JsonParser.parseString(created.second), JsonParser.parseString(body))
  }

  // SEP38RS-05
  @Test
  fun `test GET quote of an unknown id is 404 quote not found`() {
    val (status, body) = raw("GET", "/quote/$unknownQuoteId", walletJwt())

    assertEquals(404, status, body)
    assertEquals(
      JsonParser.parseString("""{"error":"quote not found"}"""),
      JsonParser.parseString(body)
    )
  }

  // SEP38RS-06
  @Test
  fun `test GET quote of another identity's quote is 404 quote not found`() {
    val id = jsonObject(postQuoteRaw().second).get("id").asString

    val (status, body) = raw("GET", "/quote/$id", newIdentityJwt())

    assertEquals(404, status, body)
    assertEquals(
      JsonParser.parseString("""{"error":"quote not found"}"""),
      JsonParser.parseString(body)
    )
  }

  // SEP38RS-07, SEP38DM-05
  @Test
  fun `test GET price and GET prices answer the same with or without a delivery method`() {
    assertEquals(
      sep38Client.getPrice(usdAsset, "100", usdcAsset, SEP31),
      sep38Client.getPrice(usdAsset, "100", usdcAsset, SEP31, sellDeliveryMethod = "WIRE"),
    )
    assertEquals(
      sep38Client.getPrice(usdcAsset, "100", usdAsset, SEP31),
      sep38Client.getPrice(usdcAsset, "100", usdAsset, SEP31, buyDeliveryMethod = "WIRE"),
    )
    assertEquals(
      sep38Client.getPrices(usdAsset, "100"),
      sep38Client.getPrices(usdAsset, "100", sellDeliveryMethod = "WIRE"),
    )
  }

  // --- invalid requests: the exact 400, each paired with the passing variant ---

  private fun assertBadRequest(expectedMessage: String, result: Pair<Int, String>) {
    assertEquals(400, result.first, "answered ${result.first}: ${result.second}")
    assertEquals(expectedMessage, jsonObject(result.second).get("error").asString)
  }

  private fun priceQuery(extra: String) = "/price?sell_asset=$usdAsset&buy_asset=$usdcAsset$extra"

  private fun quoteJson(extra: String) =
    """{"sell_asset":"$usdAsset","buy_asset":"$usdcAsset"$extra}"""

  private val eitherAmount = "Please provide either sell_amount or buy_amount"

  // SEP38VL-01
  @Test
  fun `test GET price with both amounts or neither is a 400`() {
    assertBadRequest(
      eitherAmount,
      raw("GET", priceQuery("&sell_amount=100&buy_amount=100&context=sep31")),
    )
    assertBadRequest(eitherAmount, raw("GET", priceQuery("&context=sep31")))
    assertEquals(200, raw("GET", priceQuery("&sell_amount=100&context=sep31")).first)
  }

  // SEP38VL-02
  @Test
  fun `test POST quote with both amounts or neither is a 400`() {
    assertBadRequest(
      eitherAmount,
      postQuoteRaw(quoteJson(""","sell_amount":"100","buy_amount":"100","context":"sep31"""")),
    )
    assertBadRequest(eitherAmount, postQuoteRaw(quoteJson(""","context":"sep31"""")))
    assertEquals(201, postQuoteRaw(quoteJson(""","sell_amount":"100","context":"sep31"""")).first)
  }

  // SEP38VL-03
  @Test
  fun `test GET price with context sep24 or none is a 400`() {
    val message = "Unsupported context. Should be one of [sep6, sep31]."
    assertBadRequest(message, raw("GET", priceQuery("&sell_amount=100&context=sep24")))
    assertBadRequest(message, raw("GET", priceQuery("&sell_amount=100")))
    assertEquals(200, raw("GET", priceQuery("&sell_amount=100&context=sep31")).first)
  }

  // SEP38VL-04
  @Test
  fun `test POST quote accepts context sep24 and refuses an unknown context`() {
    assertEquals(201, postQuoteRaw(quoteJson(""","sell_amount":"100","context":"sep24"""")).first)
    assertBadRequest(
      "Unsupported context. Should be one of [sep6, sep24, sep31].",
      postQuoteRaw(quoteJson(""","sell_amount":"100","context":"sep99"""")),
    )
  }

  private fun quoteExpiringAfter(value: String) =
    quoteJson(""","sell_amount":"100","context":"sep31","expire_after":"$value"""")

  // SEP38VL-05
  @Test
  fun `test POST quote with a malformed expire_after is a 400`() {
    assertBadRequest("expire_after is invalid", postQuoteRaw(quoteExpiringAfter("not-a-date")))
  }

  // SEP38VL-06
  @Test
  fun `test POST quote with an expire_after in the past is a 400`() {
    val tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES)
    assertBadRequest(
      "expire_after cannot be in the past",
      postQuoteRaw(quoteExpiringAfter(tenMinutesAgo.toString())),
    )
  }

  // SEP38VL-07
  @Test
  fun `test POST quote with an expire_after beyond the maximum is a 400`() {
    val eightDays = Instant.now().plus(8, ChronoUnit.DAYS)
    assertBadRequest(
      "expire_after exceeds the maximum quote expiration of 604800 seconds",
      postQuoteRaw(quoteExpiringAfter(eightDays.toString())),
    )
  }

  // SEP38VL-08
  @Test
  fun `test POST quote with an in-range expire_after expires no earlier than requested`() {
    val oneHour = Instant.now().plus(1, ChronoUnit.HOURS)
    val (status, body) = postQuoteRaw(quoteExpiringAfter(oneHour.toString()))

    assertEquals(201, status, body)
    assert(!Instant.parse(jsonObject(body).get("expires_at").asString).isBefore(oneHour)) {
      "expires_at must not be earlier than expire_after: $body"
    }
  }
}
