package org.stellar.anchor.platform.integrationtest

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.test.assertEquals
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
    val expireAfter = DateTimeFormatter.ISO_INSTANT.parse("2022-04-30T02:15:44.000Z", Instant::from)
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
}
