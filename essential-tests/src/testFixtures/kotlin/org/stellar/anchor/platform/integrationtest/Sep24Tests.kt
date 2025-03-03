package org.stellar.anchor.platform.integrationtest

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.MethodOrderer.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.springframework.web.util.UriComponentsBuilder
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.platform.PatchTransactionsRequest
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.MoreInfoUrlJwt.Sep24MoreInfoUrlJwt
import org.stellar.anchor.auth.Sep24InteractiveUrlJwt
import org.stellar.anchor.platform.AbstractIntegrationTests
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.gson
import org.stellar.anchor.platform.printRequest
import org.stellar.anchor.platform.printResponse
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.StringHelper.json
import org.stellar.walletsdk.anchor.IncompleteDepositTransaction
import org.stellar.walletsdk.anchor.IncompleteWithdrawalTransaction
import org.stellar.walletsdk.asset.IssuedAssetId

// The tests must be executed in order. Currency is disabled.
// Some of the tests depend on the result of previous tests. The lifecycle must be PER_CLASS
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(SAME_THREAD)
@TestMethodOrder(OrderAnnotation::class)
class Sep24Tests : AbstractIntegrationTests(TestConfig()) {
  private val jwtService: JwtService =
    JwtService(
      config.env["secret.sep6.more_info_url.jwt_secret"],
      config.env["secret.sep10.jwt_secret"]!!,
      config.env["secret.sep45.jwt_secret"]!!,
      config.env["secret.sep24.interactive_url.jwt_secret"]!!,
      config.env["secret.sep24.more_info_url.jwt_secret"]!!,
      config.env["secret.callback_api.auth_secret"]!!,
      config.env["secret.platform_api.auth_secret"]!!,
      null,
    )

  private val platformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)

  private lateinit var savedWithdrawTxn: IncompleteWithdrawalTransaction
  private lateinit var savedDepositTxn: IncompleteDepositTransaction

  @Test
  @Order(10)
  fun `test Sep24 info endpoint`() = runBlocking {
    printRequest("Calling GET /info")
    val info = anchor.sep24().getServicesInfo()
    JSONAssert.assertEquals(expectedSep24Info, gson.toJson(info), JSONCompareMode.LENIENT)
  }

  @Test
  @Order(20)
  fun `test Sep24 withdraw`() = runBlocking {
    printRequest("POST /transactions/withdraw/interactive")
    val withdrawRequest: HashMap<String, String> =
      gson.fromJson(withdrawRequest, object : TypeToken<HashMap<String, String>>() {}.type)
    val response =
      anchor
        .sep24()
        .withdraw(
          IssuedAssetId("USDC", "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"),
          token,
          withdrawRequest,
          "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
        )
    printResponse(
      "POST /transactions/withdraw/interactive response:",
      Json.encodeToString(response),
    )
    savedWithdrawTxn =
      anchor.sep24().getTransaction(response.id, token) as IncompleteWithdrawalTransaction
    assertEquals(response.id, savedWithdrawTxn.id)
    assertNotNull(savedWithdrawTxn.moreInfoUrl)
    assertEquals("INCOMPLETE", savedWithdrawTxn.status.name)
    assertEquals(
      "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
      savedWithdrawTxn.from?.address,
    )

    val requestLang = "es-AR"
    val langTx = anchor.sep24().getTransactionBy(token, id = response.id, lang = requestLang)
    val claims =
      jwtService
        .decode(
          UriComponentsBuilder.fromUriString(langTx.moreInfoUrl).build().queryParams["token"]!![0],
          Sep24MoreInfoUrlJwt::class.java,
        )
        .claims["data"]
    var lang = (claims as Map<String, String>)["lang"]
    assertEquals(requestLang, lang)

    // check the returning Sep24InteractiveUrlJwt
    val params = UriComponentsBuilder.fromUriString(response.url).build().queryParams
    val cipher = params["token"]!![0]
    val jwt = jwtService.decode(cipher, Sep24InteractiveUrlJwt::class.java)
    assertEquals(response.id, jwt.jti)
  }

  @Test
  @Order(30)
  fun `test Sep24 deposit`() = runBlocking {
    printRequest("POST /transactions/withdraw/interactive")
    val depositRequest = GsonUtils.fromJsonToMap(depositRequestJson)
    val response =
      anchor
        .sep24()
        .deposit(
          IssuedAssetId(depositRequest["asset_code"]!!, depositRequest["asset_issuer"]!!),
          token,
          depositRequest as HashMap<String, String>,
        )
    printResponse("POST /transactions/deposit/interactive response:", Json.encodeToString(response))
    savedDepositTxn =
      anchor.sep24().getTransaction(response.id, token) as IncompleteDepositTransaction
    printResponse(Json.encodeToString(savedDepositTxn))
    assertEquals(savedDepositTxn.id, response.id)
    assertNotNull(savedDepositTxn.moreInfoUrl)
    assertEquals("INCOMPLETE", savedDepositTxn.status.name)
    assertEquals(
      "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      savedDepositTxn.to?.address,
    )
    // check the returning Sep24InteractiveUrlJwt
    val params = UriComponentsBuilder.fromUriString(response.url).build().queryParams
    val cipher = params["token"]!![0]
    val jwt = jwtService.decode(cipher, Sep24InteractiveUrlJwt::class.java)
    assertEquals(response.id, jwt.jti)
    assertNotNull(jwt.claims["data"])
    assertNotNull((jwt.claims["data"] as Map<*, *>)["asset"])
  }

  /*
    The following test case is not supported by the wallet sdk. It is commented out until a proper solution is found.

    private val depositRequestNoIssuerJson =
      """{
      "amount": "10",
      "asset_code": "USDC",
      "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      "lang": "en"
  }"""

    data class AssetIdNoIssuer(val code: String) : StellarAssetId {
      override val id = "$code"
      override fun toString() = sep38
    }
    @Order(40)
    private fun `test Sep24 deposit no issuer`() = runBlocking {
      printRequest("POST /transactions/withdraw/interactive")
      val depositRequest = GsonUtils.fromJsonToMap(depositRequestNoIssuerJson)
      val response =
        anchor
          .sep24()
          .deposit(
            AssetIdNoIssuer(depositRequest["asset_code"]!!),
            token,
            depositRequest as HashMap<String, String>
          )
      printResponse("POST /transactions/deposit/interactive response:", response)
      savedDepositTxn =
        anchor.sep24().getTransaction(response.id, token) as IncompleteDepositTransaction
      printResponse(savedDepositTxn)
      assertEquals(savedDepositTxn.id, response.id)
      assertNotNull(savedDepositTxn.moreInfoUrl)
      assertEquals("INCOMPLETE", savedDepositTxn.status.name)
      assertEquals(
        "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
        savedDepositTxn.to?.address
      )
      // check the returning Sep24InteractiveUrlJwt
      val params = UriComponentsBuilder.fromUriString(response.url).build().queryParams
      val cipher = params["token"]!![0]
      val jwt = jwtService.decode(cipher, Sep24InteractiveUrlJwt::class.java)
      assertEquals(response.id, jwt.jti)
      assertNotNull(jwt.claims["data"])
      assertNotNull((jwt.claims["data"] as HashMap<String, String>)["asset"])
    }
  */
  @Test
  @Order(50)
  fun `test Sep24 GET transaction and check the JWT`() = runBlocking {
    val txn =
      anchor
        .sep24()
        .getTransactionBy(
          token,
          savedDepositTxn.id,
          "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
        )

    val params = UriComponentsBuilder.fromUriString(txn.moreInfoUrl).build().queryParams
    val cipher = params["token"]!![0]
    val jwt = jwtService.decode(cipher, Sep24MoreInfoUrlJwt::class.java)
    assertEquals(txn.id, jwt.jti)
  }

  @Test
  @Order(60)
  fun `test PlatformAPI GET transaction for deposit and withdrawal`() {
    val actualWithdrawTxn = platformApiClient.getTransaction(savedWithdrawTxn.id)
    assertEquals(actualWithdrawTxn.id, savedWithdrawTxn.id)
    JSONAssert.assertEquals(
      expectedWithdrawTransactionResponse,
      json(actualWithdrawTxn),
      JSONCompareMode.LENIENT,
    )

    val actualDepositTxn = platformApiClient.getTransaction(savedDepositTxn.id)
    printResponse(actualDepositTxn)
    assertEquals(actualDepositTxn.id, savedDepositTxn.id)
    JSONAssert.assertEquals(
      expectedDepositTransactionResponse,
      json(actualDepositTxn),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  @Order(70)
  fun `test patch, get and compare`() {
    val patch = gson.fromJson(patchWithdrawTransactionRequest, PatchTransactionsRequest::class.java)
    // create patch request and patch
    patch.records[0].transaction.id = savedWithdrawTxn.id
    patch.records[1].transaction.id = savedDepositTxn.id
    platformApiClient.patchTransaction(patch)

    // check if the patched transactions are as expected
    var afterPatchWithdraw = platformApiClient.getTransaction(savedWithdrawTxn.id)
    assertEquals(afterPatchWithdraw.id, savedWithdrawTxn.id)
    JSONAssert.assertEquals(
      expectedAfterPatchWithdraw,
      json(afterPatchWithdraw),
      JSONCompareMode.LENIENT,
    )

    var afterPatchDeposit = platformApiClient.getTransaction(savedDepositTxn.id)
    assertEquals(afterPatchDeposit.id, savedDepositTxn.id)
    JSONAssert.assertEquals(
      expectedAfterPatchDeposit,
      json(afterPatchDeposit),
      JSONCompareMode.LENIENT,
    )

    // Test patch idempotency
    afterPatchWithdraw = platformApiClient.getTransaction(savedWithdrawTxn.id)
    assertEquals(afterPatchWithdraw.id, savedWithdrawTxn.id)
    JSONAssert.assertEquals(
      expectedAfterPatchWithdraw,
      json(afterPatchWithdraw),
      JSONCompareMode.LENIENT,
    )

    afterPatchDeposit = platformApiClient.getTransaction(savedDepositTxn.id)
    assertEquals(afterPatchDeposit.id, savedDepositTxn.id)
    JSONAssert.assertEquals(
      expectedAfterPatchDeposit,
      json(afterPatchDeposit),
      JSONCompareMode.LENIENT,
    )
  }

  @Test
  @Order(80)
  fun `test GET transactions with bad ids`() {
    val badTxnIds = listOf("null", "bad id", "123", null)
    for (txnId in badTxnIds) {
      assertThrows<SepException> { platformApiClient.getTransaction(txnId) }
    }
  }
}

private const val withdrawRequest =
  """{
    "amount": "10",
    "asset_code": "USDC",
    "asset_issuer": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "account": "GAIUIZPHLIHQEMNJGSZKCEUWHAZVGUZDBDMO2JXNAJZZZVNSVHQCEWJ4",
    "lang": "en"
}"""

private const val depositRequestJson =
  """{
    "amount": "10",
    "asset_code": "USDC",
    "asset_issuer": "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "account": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
    "lang": "en"
}"""

private const val patchWithdrawTransactionRequest =
  """
{
  "records": [
    {
      "transaction": {
        "id": "",
        "status": "completed",
        "amount_in": {
          "amount": "10",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        },
        "amount_out": {
          "amount": "10",
          "asset": "iso4217:USD"
        },
        "fee_details": {
          "total": "1",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        },
        "message": "this is the message",
        "refunds": {
          "amount_refunded": {
            "amount": "1",
            "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
          },
          "amount_fee": {
            "amount": "0.1",
            "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
          },
          "payments": [
            {
              "id": 1,
              "id_type": "stellar",
              "amount": {
                "amount": "0.6",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee": {
                "amount": "0.1",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              }
            },
            {
              "id": 2,
              "id_type": "stellar",
              "amount": {
                "amount": "0.4",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              },
              "fee": {
                "amount": "0",
                "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
              }
            }
          ]
        }
      }
    },
    {
      "transaction": {
        "id": "",
        "status": "completed",
        "amount_in": {
          "amount": "100",
          "asset": "iso4217:USD"
        },
        "amount_out": {
          "amount": "100",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        },
        "fee_details": {
          "total": "1",
          "asset": "iso4217:USD"
        },
        "message": "this is the message"
      }
    }
  ]
}    
"""

private const val expectedAfterPatchWithdraw =
  """
{
  "sep": "24",
  "kind": "withdrawal",
  "status": "completed",
  "amount_in": {
    "amount": "10",
    "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  },
  "amount_out": {
    "amount": "10",
    "asset": "iso4217:USD"
  },
  "fee_details": {
    "total": "1",
    "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
  },
  "refunds": {
    "amount_refunded": {
      "amount": "1",
      "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    },
    "amount_fee": {
      "amount": "0.1",
      "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    },
    "payments": [
      {
        "id": "1",
        "id_type": "stellar",
        "amount": {
          "amount": "0.6",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        },
        "fee": {
          "amount": "0.1",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        }
      },
      {
        "id": "2",
        "id_type": "stellar",
        "amount": {
          "amount": "0.4",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        },
        "fee": {
          "amount": "0",
          "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
        }
      }
    ]
  }
}"""

private const val expectedAfterPatchDeposit =
  """
  {
    "sep": "24",
    "kind": "deposit",
    "status": "completed",
    "amount_in": {
      "amount": "100",
      "asset": "iso4217:USD"
    },
    "amount_out": {
      "amount": "100",
      "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
    },
    "fee_details": {
      "total": "1",
      "asset": "iso4217:USD"
    }
  }
"""

private const val expectedSep24Info =
  """
  {
    "deposit": {
      "native": { "enabled": true, "minAmount": 0.0, "maxAmount": 10.0 },
      "USDC": { "enabled": true, "minAmount": 0.0, "maxAmount": 10.0 }
    },
    "withdraw": {
      "native": { "enabled": true, "minAmount": 0.0, "maxAmount": 10.0 },
      "USDC": { "enabled": true, "minAmount": 0.0, "maxAmount": 10.0 }
    },
    "fee": { "enabled": false },
    "features": { "accountCreation": false, "claimableBalances": false }
  }
  """

private const val expectedWithdrawTransactionResponse =
  """
  {
    "sep": "24",
    "kind": "withdrawal",
    "status": "incomplete"
  }
"""

private const val expectedDepositTransactionResponse =
  """
  {
    "sep": "24",
    "kind": "deposit",
    "status": "incomplete"
  }
"""
