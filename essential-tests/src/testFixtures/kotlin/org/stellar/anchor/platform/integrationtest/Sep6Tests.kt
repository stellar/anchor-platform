package org.stellar.anchor.platform.integrationtest

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.sep.sep38.Sep38Context
import org.stellar.anchor.client.Sep38Client
import org.stellar.anchor.client.Sep6Client
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.anchor.platform.gson
import org.stellar.anchor.util.Log
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.horizon.SigningKeyPair

class Sep6Tests : IntegrationTestBase(TestConfig()) {
  private val sep6Client = Sep6Client(toml.getString("TRANSFER_SERVER"), token.token)
  private val sep38Client = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), this.token.token)
  private val clientWalletAccount = KeyPair.fromSecretSeed(CLIENT_WALLET_SECRET).accountId

  private fun authenticateWithMemo(keyPair: SigningKeyPair, memoId: ULong): String {
    return runBlocking { anchor.auth().authenticate(keyPair, memoId = memoId) }.token
  }

  private fun authenticateWithoutMemo(keyPair: SigningKeyPair): String {
    return runBlocking { anchor.auth().authenticate(keyPair) }.token
  }

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
  fun `test sep6 GET transactions does not leak another memo's transactions on a shared account`() {
    val sharedKeyPair = SigningKeyPair(KeyPair.random())
    val noMemoJwt = authenticateWithoutMemo(sharedKeyPair)
    val memoAJwt = authenticateWithMemo(sharedKeyPair, 111UL)
    val memoBJwt = authenticateWithMemo(sharedKeyPair, 222UL)

    val noMemoClient = Sep6Client(toml.getString("TRANSFER_SERVER"), noMemoJwt)
    val memoAClient = Sep6Client(toml.getString("TRANSFER_SERVER"), memoAJwt)
    val memoBClient = Sep6Client(toml.getString("TRANSFER_SERVER"), memoBJwt)

    fun depositRequest() =
      mapOf(
        "asset_code" to "USDC",
        "account" to sharedKeyPair.address,
        "amount" to "1",
        "type" to "SWIFT",
      )

    val noMemoTxnId = noMemoClient.deposit(depositRequest()).id!!
    val memoATxnId = memoAClient.deposit(depositRequest()).id!!
    val memoBTxnId = memoBClient.deposit(depositRequest()).id!!

    val listedIds =
      noMemoClient
        .getTransactions(mapOf("asset_code" to "USDC", "account" to sharedKeyPair.address))
        .transactions
        .map { it.id }

    Assertions.assertTrue(listedIds.contains(noMemoTxnId)) {
      "caller's own no-memo transaction should be visible in its own list"
    }
    Assertions.assertFalse(listedIds.contains(memoATxnId)) {
      "GET /transactions leaked another memo's transaction ($memoATxnId) to a bare-account caller sharing the same Stellar account"
    }
    Assertions.assertFalse(listedIds.contains(memoBTxnId)) {
      "GET /transactions leaked another memo's transaction ($memoBTxnId) to a bare-account caller sharing the same Stellar account"
    }
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

  @Test
  fun `test sep6 deposit rejects account outside destination policy`() {
    val freshAccount = KeyPair.random().accountId
    val ex =
      assertThrows<SepException> {
        sep6Client.deposit(
          mapOf(
            "asset_code" to "USDC",
            "account" to freshAccount,
            "amount" to "1",
            "type" to "SWIFT"
          )
        )
      }
    assert(ex.message!!.contains("Provided 'account' is not allowed")) {
      "Expected destination policy error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 deposit-exchange rejects account outside destination policy`() {
    val freshAccount = KeyPair.random().accountId
    val ex =
      assertThrows<SepException> {
        sep6Client.deposit(
          mapOf(
            "destination_asset" to "USDC",
            "source_asset" to "iso4217:USD",
            "amount" to "1",
            "account" to freshAccount,
            "type" to "SWIFT",
          ),
          exchange = true,
        )
      }
    assert(ex.message!!.contains("Provided 'account' is not allowed")) {
      "Expected destination policy error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 withdraw rejects account outside destination policy`() {
    val freshAccount = KeyPair.random().accountId
    val ex =
      assertThrows<SepException> {
        sep6Client.withdraw(
          mapOf(
            "asset_code" to "USDC",
            "account" to freshAccount,
            "amount" to "1",
            "type" to "bank_account",
          )
        )
      }
    assert(ex.message!!.contains("Provided 'account' is not allowed")) {
      "Expected destination policy error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 withdraw-exchange rejects account outside destination policy`() {
    val freshAccount = KeyPair.random().accountId
    val ex =
      assertThrows<SepException> {
        sep6Client.withdraw(
          mapOf(
            "source_asset" to "USDC",
            "destination_asset" to "iso4217:USD",
            "amount" to "1",
            "account" to freshAccount,
            "type" to "bank_account",
          ),
          exchange = true,
        )
      }
    assert(ex.message!!.contains("Provided 'account' is not allowed")) {
      "Expected destination policy error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 deposit rejects request without JWT`() {
    val noAuthClient = Sep6Client(toml.getString("TRANSFER_SERVER"), null)
    assertThrows<SepNotAuthorizedException> {
      noAuthClient.deposit(
        mapOf(
          "asset_code" to "USDC",
          "account" to clientWalletAccount,
          "amount" to "1",
          "type" to "SWIFT",
        )
      )
    }
  }

  @Test
  fun `test sep6 withdraw rejects request without JWT`() {
    val noAuthClient = Sep6Client(toml.getString("TRANSFER_SERVER"), null)
    assertThrows<SepNotAuthorizedException> {
      noAuthClient.withdraw(
        mapOf("asset_code" to "USDC", "type" to "bank_account", "amount" to "1")
      )
    }
  }

  @Test
  fun `test sep6 deposit rejects request without asset_code`() {
    val ex =
      assertThrows<SepException> {
        sep6Client.deposit(
          mapOf("account" to clientWalletAccount, "amount" to "1", "type" to "SWIFT")
        )
      }
    assert(ex.message!!.contains("asset_code")) {
      "Expected a missing 'asset_code' parameter error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 withdraw rejects request without asset_code`() {
    val ex =
      assertThrows<SepException> {
        sep6Client.withdraw(mapOf("type" to "bank_account", "amount" to "1"))
      }
    assert(ex.message!!.contains("asset_code")) {
      "Expected a missing 'asset_code' parameter error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 deposit rejects unsupported asset_code`() {
    val ex =
      assertThrows<SepException> {
        sep6Client.deposit(
          mapOf(
            "asset_code" to "DOES_NOT_EXIST",
            "account" to clientWalletAccount,
            "amount" to "1",
            "type" to "SWIFT",
          )
        )
      }
    assert(ex.message!!.contains("invalid operation for asset")) {
      "Expected an unsupported-asset error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 withdraw rejects unsupported asset_code`() {
    val ex =
      assertThrows<SepException> {
        sep6Client.withdraw(
          mapOf("asset_code" to "DOES_NOT_EXIST", "type" to "bank_account", "amount" to "1")
        )
      }
    assert(ex.message!!.contains("invalid operation for asset")) {
      "Expected an unsupported-asset error but got: ${ex.message}"
    }
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
