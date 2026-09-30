package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.springframework.web.util.UriComponentsBuilder
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.platform.PatchTransactionsRequest
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.MoreInfoUrlJwt.Sep24MoreInfoUrlJwt
import org.stellar.anchor.auth.Sep24InteractiveUrlJwt
import org.stellar.anchor.client.Sep24Client
import org.stellar.anchor.platform.*
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.StringHelper.json
import org.stellar.sdk.KeyPair
import org.stellar.walletsdk.anchor.IncompleteDepositTransaction
import org.stellar.walletsdk.anchor.IncompleteWithdrawalTransaction
import org.stellar.walletsdk.anchor.auth
import org.stellar.walletsdk.asset.IssuedAssetId
import org.stellar.walletsdk.horizon.SigningKeyPair

// The tests must be executed in order. Currency is disabled.
// Some of the tests depend on the result of previous tests. The lifecycle must be PER_CLASS
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(SAME_THREAD)
@TestMethodOrder(OrderAnnotation::class)
class Sep24Tests : IntegrationTestBase(TestConfig()) {
  private val jwtService: JwtService =
    JwtService(
      config.env["secret.sep6.more_info_url.jwt_secret"],
      config.env["secret.sep10.jwt_secret"]!!,
      config.env["secret.sep45.jwt_secret"]!!,
      config.env["secret.sep24.interactive_url.jwt_secret"]!!,
      config.env["secret.sep24.more_info_url.jwt_secret"]!!,
      config.env["secret.callback_api.auth_secret"]!!,
      config.env["secret.platform_api.auth_secret"]!!,
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

  private val sep24Client = Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), token.token)
  private val noAuthSep24Client = Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), null)

  /**
   * A 400 body carries `{"error": "..."}`; [SepClient.handleResponse] preserves it raw rather than
   * parsing it, so tests that need the exact message extract it themselves.
   */
  private fun errorMessage(ex: SepException): String =
    JsonParser.parseString(ex.message).asJsonObject.get("error").asString

  /** A fresh, isolated account: counting and ordering assertions never share state (AD-03). */
  private class TestAccount(val accountId: String, val jwt: String, val client: Sep24Client)

  private val http = OkHttpClient()

  private fun newAccount(): TestAccount {
    val keyPair = KeyPair.random()
    val jwt = runBlocking { anchor.auth().authenticate(SigningKeyPair(keyPair)) }.token
    return TestAccount(
      keyPair.accountId,
      jwt,
      Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), jwt),
    )
  }

  /** [issuer] `null` omits `asset_issuer`, which the deposit then stores as null. */
  private fun createDeposit(account: TestAccount, issuer: String? = USDC_GDQO_ISSUER): String =
    account.client
      .deposit(
        buildMap {
          put("asset_code", "USDC")
          put("amount", "1")
          if (issuer != null) put("asset_issuer", issuer)
        }
      )
      .id

  private fun createWithdrawal(account: TestAccount): String =
    account.client
      .withdraw(mapOf("asset_code" to "USDC", "asset_issuer" to USDC_GDQO_ISSUER, "amount" to "1"))
      .id

  /**
   * Reads `GET /transactions` as the raw `transactions` array, with or without a `Content-Type`.
   * Gson silently nulls absent fields, so a parsed object cannot back a schema assertion.
   */
  private fun listRaw(
    account: TestAccount,
    query: Map<String, String>,
    contentType: String? = null,
  ): JsonArray {
    val url = "${toml.getString("TRANSFER_SERVER_SEP0024")}/transactions".toHttpUrl().newBuilder()
    query.forEach { (key, value) -> url.addQueryParameter(key, value) }
    val request =
      Request.Builder()
        .url(url.build())
        .header("Authorization", "Bearer ${account.jwt}")
        .apply { if (contentType != null) header("Content-Type", contentType) }
        .get()
        .build()
    http.newCall(request).execute().use { response ->
      val body = response.body?.string()
      assertEquals(200, response.code) {
        "GET /transactions (Content-Type=$contentType) answered ${response.code}: $body"
      }
      return JsonParser.parseString(body).asJsonObject.getAsJsonArray("transactions")
    }
  }

  private fun JsonArray.ids(): List<String> = map { it.asJsonObject.get("id").asString }

  @Test
  fun `test sep24 GET transactions answers the same with a JSON Content-Type`() {
    val account = newAccount()
    val created = setOf(createDeposit(account), createDeposit(account))
    val query = mapOf("asset_code" to "USDC")

    val withoutHeader = listRaw(account, query).ids()
    val withHeader = listRaw(account, query, "application/json").ids()
    val viaClient = account.client.getTransactions(query).transactions.map { it.id }

    assertEquals(created, withoutHeader.toSet()) {
      "the request without a Content-Type must list both fixture deposits, or the comparison below is vacuous"
    }
    assertEquals(withoutHeader, withHeader)
    assertEquals(withoutHeader, viaClient)
  }

  @Test
  fun `test sep24 GET transactions reports a missing asset_code despite a JSON Content-Type`() {
    val ex = assertThrows<SepValidationException> { sep24Client.getTransactions(mapOf()) }
    assertEquals("The \"asset_code\" parameter is missing.", errorMessage(ex))
  }

  @Test
  fun `test sep24 GET transaction rejects request without JWT`() {
    assertThrows<SepNotAuthorizedException> {
      noAuthSep24Client.getTransaction(mapOf("id" to UUID.randomUUID().toString()))
    }
  }

  @Test
  fun `test sep24 GET transaction rejects request naming no transaction`() {
    val ex = assertThrows<SepValidationException> { sep24Client.getTransaction(mapOf()) }
    assertEquals(
      "One of id, stellar_transaction_id or external_transaction_id is required.",
      errorMessage(ex),
    )
  }

  @Test
  fun `test sep24 GET transaction returns 404 for an unknown id`() {
    val ex =
      assertThrows<SepNotFoundException> {
        sep24Client.getTransaction(mapOf("id" to UUID.randomUUID().toString()))
      }
    assertEquals("transaction not found", ex.message)
  }

  @Test
  fun `test sep24 GET transaction returns 404 for an unknown external_transaction_id`() {
    val ex =
      assertThrows<SepNotFoundException> {
        sep24Client.getTransaction(
          mapOf("external_transaction_id" to "unknown-${UUID.randomUUID()}")
        )
      }
    assertEquals("transaction not found", ex.message)
  }

  @Test
  fun `test sep24 GET transaction returns 404 for an unknown stellar_transaction_id`() {
    val ex =
      assertThrows<SepNotFoundException> {
        sep24Client.getTransaction(
          mapOf("stellar_transaction_id" to "unknown-${UUID.randomUUID()}")
        )
      }
    assertEquals("transaction not found", ex.message)
  }

  @Test
  fun `test sep24 GET transaction resolves a transaction by external_transaction_id`() {
    val depositId = sep24Client.deposit(mapOf("asset_code" to "USDC")).id

    val externalTransactionId = "sep24-404s-external-${UUID.randomUUID()}"
    val rpcActionRequests: List<RpcRequest> =
      gson.fromJson(
        SEP24_EXTERNAL_TRANSACTION_ID_FLOW_ACTION_REQUESTS.replace("%TX_ID%", depositId)
          .replace("%EXTERNAL_TRANSACTION_ID%", externalTransactionId),
        object : TypeToken<List<RpcRequest>>() {}.type,
      )
    platformApiClient.sendRpcRequest(rpcActionRequests).use { response ->
      assertTrue(response.isSuccessful) { "RPC setup failed with HTTP ${response.code}" }
    }

    val found =
      sep24Client.getTransaction(mapOf("external_transaction_id" to externalTransactionId))
    assertEquals(depositId, found.transaction.id)
  }

  @Test
  fun `test sep24 deposit rejects request without JWT`() {
    assertThrows<SepNotAuthorizedException> { noAuthSep24Client.deposit(validInteractiveRequest) }
  }

  @Test
  fun `test sep24 deposit rejects request without asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.deposit(validInteractiveRequest - "asset_code" - "asset_issuer")
      }
    assertEquals("missing 'asset_code'", errorMessage(ex))
  }

  @Test
  fun `test sep24 deposit rejects an invalid account`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.deposit(validInteractiveRequest + ("account" to "not a valid account"))
      }
    assertEquals("invalid account not a valid account", errorMessage(ex))
  }

  @Test
  fun `test sep24 deposit rejects unsupported asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.deposit(
          validInteractiveRequest - "asset_issuer" + ("asset_code" to "NOT_SUPPORTED")
        )
      }
    assertEquals("invalid operation for asset NOT_SUPPORTED", errorMessage(ex))
  }

  @Test
  fun `test sep24 multipart deposit rejects request without asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.depositMultipart(validInteractiveRequest - "asset_code" - "asset_issuer")
      }
    assertEquals("missing 'asset_code'", errorMessage(ex))
  }

  @Test
  fun `test sep24 multipart deposit rejects unsupported asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.depositMultipart(
          validInteractiveRequest - "asset_issuer" + ("asset_code" to "NOT_SUPPORTED")
        )
      }
    assertEquals("invalid operation for asset NOT_SUPPORTED", errorMessage(ex))
  }

  @Test
  fun `test sep24 multipart deposit rejects an invalid account`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.depositMultipart(validInteractiveRequest + ("account" to "not a valid account"))
      }
    assertEquals("invalid account not a valid account", errorMessage(ex))
  }

  @Test
  fun `test sep24 multipart withdraw rejects request without asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.withdrawMultipart(validInteractiveRequest - "asset_code" - "asset_issuer")
      }
    assertEquals("missing 'asset_code'", errorMessage(ex))
  }

  @Test
  fun `test sep24 multipart withdraw rejects unsupported asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.withdrawMultipart(
          validInteractiveRequest - "asset_issuer" + ("asset_code" to "NOT_SUPPORTED")
        )
      }
    assertEquals("invalid operation for asset NOT_SUPPORTED", errorMessage(ex))
  }

  @Test
  fun `test sep24 multipart withdraw rejects an invalid account`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.withdrawMultipart(
          validInteractiveRequest + ("account" to "not a valid account")
        )
      }
    assertEquals("invalid account not a valid account", errorMessage(ex))
  }

  @Test
  fun `test sep24 withdraw rejects request without JWT`() {
    assertThrows<SepNotAuthorizedException> { noAuthSep24Client.withdraw(validInteractiveRequest) }
  }

  @Test
  fun `test sep24 withdraw rejects request without asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.withdraw(validInteractiveRequest - "asset_code" - "asset_issuer")
      }
    assertEquals("missing 'asset_code'", errorMessage(ex))
  }

  @Test
  fun `test sep24 withdraw rejects unsupported asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.withdraw(
          validInteractiveRequest - "asset_issuer" + ("asset_code" to "NOT_SUPPORTED")
        )
      }
    assertEquals("invalid operation for asset NOT_SUPPORTED", errorMessage(ex))
  }

  @Test
  fun `test sep24 withdraw rejects an invalid account`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.withdraw(validInteractiveRequest + ("account" to "not a valid account"))
      }
    assertEquals("invalid account not a valid account", errorMessage(ex))
  }
}

private const val USDC_GDQO_ISSUER = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"

/** A request the interactive endpoints accept; each negative test breaks exactly one field. */
private val validInteractiveRequest =
  mapOf(
    "asset_code" to "USDC",
    "asset_issuer" to "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    "account" to "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
  )

/**
 * The first two steps of `SEP_24_DEPOSIT_COMPLETE_SHORT_FLOW_ACTION_REQUESTS`
 * (Sep24PlatformApiTests.kt), with the external transaction id left to the caller so each run looks
 * up a value no other test uses.
 */
private const val SEP24_EXTERNAL_TRANSACTION_ID_FLOW_ACTION_REQUESTS =
  """
[
  {
    "id": "1",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 1",
      "amount_in": { "amount": "100", "asset": "iso4217:USD" },
      "amount_out": {
        "amount": "95",
        "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
      },
      "fee_details": { "total": "5", "asset": "iso4217:USD" },
      "amount_expected": { "amount": "100" }
    }
  },
  {
    "id": "2",
    "method": "notify_offchain_funds_received",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "test message 2",
      "funds_received_at": "2023-07-04T12:34:56Z",
      "external_transaction_id": "%EXTERNAL_TRANSACTION_ID%",
      "amount_in": { "amount": "100" }
    }
  }
]
"""

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
