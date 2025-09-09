package org.stellar.anchor.platform.integrationtest

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.sep.sep38.Sep38Context
import org.stellar.anchor.client.Sep38Client
import org.stellar.anchor.client.Sep6Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.anchor.platform.gson
import org.stellar.anchor.util.Log
import org.stellar.sdk.KeyPair

class Sep6Tests : IntegrationTestBase(TestConfig()) {
  private val sep6Client = Sep6Client(toml.getString("TRANSFER_SERVER"), token.token)
  private val sep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), this.token.token)
  private val clientWalletAccount = KeyPair.fromSecretSeed(CLIENT_WALLET_SECRET).accountId

  @Test
  fun `test Sep6 info endpoint`() {
    val info = sep6Client.getInfo()
    JSONAssert.assertEquals(expectedSep6Info, gson.toJson(info), JSONCompareMode.STRICT)
  }

  @Test
  fun `test sep6 deposit`() {
    val request =
      mapOf(
        "asset_code" to "USDC",
        "account" to clientWalletAccount,
        "amount" to "1",
        "type" to "SWIFT",
      )
    val response = sep6Client.deposit(request)
    Log.info("GET /deposit response: $response")
    assert(!response.id.isNullOrEmpty())

    val savedDepositTxn = sep6Client.getTransaction(mapOf("id" to response.id!!))
    JSONAssert.assertEquals(
      expectedSep6DepositResponse,
      gson.toJson(savedDepositTxn),
      JSONCompareMode.LENIENT,
    )
    Assertions.assertNotNull(savedDepositTxn.transaction.moreInfoUrl)
  }

  @Test
  fun `test sep6 deposit-exchange without quote`() {
    val request =
      mapOf(
        "destination_asset" to "USDC",
        "source_asset" to "iso4217:USD",
        "amount" to "1",
        "account" to clientWalletAccount,
        "type" to "SWIFT",
      )

    val response = sep6Client.deposit(request, exchange = true)
    Log.info("GET /deposit-exchange response: $response")
    assert(!response.id.isNullOrEmpty())

    val savedDepositTxn = sep6Client.getTransaction(mapOf("id" to response.id!!))
    JSONAssert.assertEquals(
      expectedSep6DepositExchangeResponse,
      gson.toJson(savedDepositTxn),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test sep6 deposit-exchange with quote`() {
    val quoteId =
      postQuote(
        "iso4217:USD",
        "10",
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
      )
    val request =
      mapOf(
        "destination_asset" to "USDC",
        "source_asset" to "iso4217:USD",
        "amount" to "10",
        "account" to clientWalletAccount,
        "type" to "SWIFT",
        "quote_id" to quoteId,
      )

    val response = sep6Client.deposit(request, exchange = true)
    Log.info("GET /deposit-exchange response: $response")
    assert(!response.id.isNullOrEmpty())

    val savedDepositTxn = sep6Client.getTransaction(mapOf("id" to response.id!!))
    JSONAssert.assertEquals(
      expectedSep6DepositExchangeWithQuoteResponse,
      gson.toJson(savedDepositTxn),
      JSONCompareMode.LENIENT,
    )
    Assertions.assertNotNull(savedDepositTxn.transaction.moreInfoUrl)
  }

  @Test
  fun `test sep6 withdraw`() {
    val request = mapOf("asset_code" to "USDC", "type" to "bank_account", "amount" to "1")
    val response = sep6Client.withdraw(request)
    Log.info("GET /withdraw response: $response")
    assert(!response.id.isNullOrEmpty())

    val savedWithdrawTxn = sep6Client.getTransaction(mapOf("id" to response.id!!))
    JSONAssert.assertEquals(
      expectedSep6WithdrawResponse,
      gson.toJson(savedWithdrawTxn),
      JSONCompareMode.LENIENT,
    )
    Assertions.assertNotNull(savedWithdrawTxn.transaction.moreInfoUrl)
  }

  @Test
  fun `test sep6 withdraw-exchange without quote`() {
    val request =
      mapOf(
        "destination_asset" to "iso4217:USD",
        "source_asset" to "USDC",
        "amount" to "1",
        "type" to "bank_account",
      )

    val response = sep6Client.withdraw(request, exchange = true)
    Log.info("GET /withdraw-exchange response: $response")
    assert(!response.id.isNullOrEmpty())

    val savedDepositTxn = sep6Client.getTransaction(mapOf("id" to response.id!!))
    JSONAssert.assertEquals(
      expectedSep6WithdrawExchangeResponse,
      gson.toJson(savedDepositTxn),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  fun `test sep6 withdraw-exchange with quote`() {
    val quoteId =
      postQuote(
        "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        "10",
        "iso4217:USD",
      )
    val request =
      mapOf(
        "destination_asset" to "iso4217:USD",
        "source_asset" to "USDC",
        "amount" to "10",
        "type" to "bank_account",
        "quote_id" to quoteId,
      )

    val response = sep6Client.withdraw(request, exchange = true)
    Log.info("GET /withdraw-exchange response: $response")
    assert(!response.id.isNullOrEmpty())

    val savedWithdrawTxn = sep6Client.getTransaction(mapOf("id" to response.id!!))
    JSONAssert.assertEquals(
      expectedSep6WithdrawExchangeWithQuoteResponse,
      gson.toJson(savedWithdrawTxn),
      JSONCompareMode.LENIENT,
    )
    Assertions.assertNotNull(savedWithdrawTxn.transaction.moreInfoUrl)
  }

  private fun postQuote(sellAsset: String, sellAmount: String, buyAsset: String): String {
    return sep38Client.postQuote(sellAsset, sellAmount, buyAsset, Sep38Context.SEP6).id
  }

  companion object {

    private val expectedSep6Info =
      """
      {
        "deposit": {
          "native": {
            "enabled": true,
            "authentication_required": true,
            "min_amount": 0,
            "max_amount": 10,
            "funding_methods": ["SEPA", "SWIFT"],
            "fields": {
              "type": {
                "description": "type of deposit to make",
                "choices": ["SEPA", "SWIFT"],
                "optional": true
              }
            }
          },
          "USDC": {
            "enabled": true,
            "authentication_required": true,
            "min_amount": 0,
            "max_amount": 10,
            "funding_methods": ["SEPA", "SWIFT"],
            "fields": {
              "type": {
                "description": "type of deposit to make",
                "choices": ["SEPA", "SWIFT"],
                "optional": true
              }
            }
          }
        },
        "deposit-exchange": {
          "native": {
            "enabled": true,
            "authentication_required": true,
            "min_amount": 0,
            "max_amount": 10,
            "funding_methods": ["SEPA", "SWIFT"],
            "fields": {
              "type": {
                "description": "type of deposit to make",
                "choices": ["SEPA", "SWIFT"],
                "optional": true
              }
            }
          },
          "USDC": {
            "enabled": true,
            "authentication_required": true,
            "min_amount": 0,
            "max_amount": 10,
            "funding_methods": ["SEPA", "SWIFT"],
            "fields": {
              "type": {
                "description": "type of deposit to make",
                "choices": ["SEPA", "SWIFT"],
                "optional": true
              }
            }
          }
        },
        "withdraw": {
          "native": {
            "enabled": true,
            "authentication_required": true,
            "min_amount": 0,
            "max_amount": 10,
            "funding_methods": ["bank_account", "cash"],
            "types": { "cash": { "fields": {} }, "bank_account": { "fields": {} } }
          },
          "USDC": {
            "enabled": true,
            "authentication_required": true,
            "min_amount": 0,
            "max_amount": 10,
            "funding_methods": ["bank_account", "cash"],
            "types": { "cash": { "fields": {} }, "bank_account": { "fields": {} } }
          }
        },
        "withdraw-exchange": {
          "native": {
            "enabled": true,
            "authentication_required": true,
            "min_amount": 0,
            "max_amount": 10,
            "funding_methods": ["bank_account", "cash"],
            "types": { "cash": { "fields": {} }, "bank_account": { "fields": {} } }
          },
          "USDC": {
            "enabled": true,
            "authentication_required": true,
            "min_amount": 0,
            "max_amount": 10,
            "funding_methods": ["bank_account", "cash"],
            "types": { "cash": { "fields": {} }, "bank_account": { "fields": {} } }
          }
        },
        "fee": { "enabled": false, "description": "Fee endpoint is not supported." },
        "transactions": { "enabled": true, "authentication_required": true },
        "transaction": { "enabled": true, "authentication_required": true },
        "features": { "account_creation": false, "claimable_balances": false }
      }
      """
        .trimIndent()

    private val expectedSep6DepositResponse =
      """
    {
        "transaction": {
            "kind": "deposit",
            "to": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
        }
    }
  """
        .trimIndent()

    private val expectedSep6DepositExchangeResponse =
      """
      {
        "transaction": {
          "kind": "deposit-exchange",
          "status": "incomplete",
          "amount_in": "1",
          "amount_in_asset": "iso4217:USD",
          "amount_out": "0",
          "amount_out_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
          "fee_details": {
              "total": "0",
              "asset": "iso4217:USD"
          },
          "to": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
        }
      }
    """
        .trimIndent()

    private val expectedSep6DepositExchangeWithQuoteResponse =
      """
      {
        "transaction": {
          "kind": "deposit-exchange",
          "status": "incomplete",
          "amount_in": "10",
          "amount_in_asset": "iso4217:USD",
          "amount_out": "8.8235",
          "amount_out_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
          "fee_details": {
            "total": "1.00",
            "asset": "iso4217:USD"
          },
          "to": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
        }
      }
    """
        .trimIndent()

    private val expectedSep6WithdrawResponse =
      """
      {
          "transaction": {
              "kind": "withdrawal",
              "status": "incomplete",
              "from": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
          }
      }
    """
        .trimIndent()

    private val expectedSep6WithdrawExchangeResponse =
      """
      {
        "transaction": {
          "kind": "withdrawal-exchange",
          "status": "incomplete",
          "amount_in": "1",
          "amount_in_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
          "amount_out": "0",
          "amount_out_asset": "iso4217:USD",
          "fee_details": {
            "total": "0",
            "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
          },
          "from": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
        }
      }
    """
        .trimIndent()

    private val expectedSep6WithdrawExchangeWithQuoteResponse =
      """
      {
        "transaction": {
          "kind": "withdrawal-exchange",
          "status": "incomplete",
          "amount_in": "10",
          "amount_in_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
          "amount_out": "8.5714",
          "amount_out_asset": "iso4217:USD",
          "fee_details": {
            "total": "1.00",
            "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
          },
          "from": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG"
        }
      }
    """
        .trimIndent()
  }
}
