package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import org.stellar.anchor.api.rpc.RpcResponse
import org.stellar.anchor.api.sep.sep38.Sep38Context
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
import org.stellar.anchor.auth.JwtService
import org.stellar.anchor.auth.MoreInfoUrlJwt.Sep24MoreInfoUrlJwt
import org.stellar.anchor.auth.Sep24InteractiveUrlJwt
import org.stellar.anchor.client.Sep24Client
import org.stellar.anchor.client.Sep38Client
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
    val (status, body) = listResponse(account, query, contentType)
    assertEquals(200, status) {
      "GET /transactions (Content-Type=$contentType) answered $status: $body"
    }
    return JsonParser.parseString(body).asJsonObject.getAsJsonArray("transactions")
  }

  /** The bare `GET /transactions` answer, as (status, body), without the client's error mapping. */
  private fun listResponse(
    account: TestAccount,
    query: Map<String, String>,
    contentType: String? = null,
  ): Pair<Int, String?> {
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
      return response.code to response.body?.string()
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

  private fun JsonObject.string(field: String): String = requireJsonString(this, field)

  /** The SEP-24 transaction shape `stellar-anchor-tests` requires, on the raw list item. */
  private fun assertListItem(
    item: JsonObject,
    kind: String,
    accountField: String,
    account: String
  ) {
    assertTrue(item.string("id").isNotEmpty())
    assertEquals(kind, item.string("kind"))
    assertEquals("incomplete", item.string("status"))
    val moreInfoUrl = URI(item.string("more_info_url"))
    assertTrue(moreInfoUrl.isAbsolute && moreInfoUrl.scheme in setOf("http", "https")) {
      "expected an absolute http(s) more_info_url but got $moreInfoUrl"
    }
    Instant.parse(item.string("started_at"))
    assertEquals(account, item.string(accountField))
  }

  private fun JsonArray.item(id: String): JsonObject {
    assertTrue(size() > 0) { "the list is empty, so no item can be checked" }
    val found = firstOrNull { it.asJsonObject.get("id").asString == id }
    assertNotNull(found) { "expected transaction $id in the list, got ${ids()}" }
    return found!!.asJsonObject
  }

  @Test
  fun `test sep24 GET transactions returns an empty list for a fresh account`() {
    val list = listRaw(newAccount(), mapOf("asset_code" to "USDC"))

    assertEquals(0, list.size())
  }

  @Test
  fun `test sep24 GET transactions lists a withdrawal`() {
    val account = newAccount()
    val withdrawalId = createWithdrawal(account)

    val list = listRaw(account, mapOf("asset_code" to "USDC"))

    assertEquals(withdrawalId, list.item(withdrawalId).string("id"))
  }

  @Test
  fun `test sep24 GET transactions returns deposits in the SEP-24 shape`() {
    val account = newAccount()
    val depositId = createDeposit(account)

    val item = listRaw(account, mapOf("asset_code" to "USDC")).item(depositId)

    assertListItem(item, "deposit", "to", account.accountId)
  }

  @Test
  fun `test sep24 GET transactions returns withdrawals in the SEP-24 shape`() {
    val account = newAccount()
    val withdrawalId = createWithdrawal(account)

    val item = listRaw(account, mapOf("asset_code" to "USDC")).item(withdrawalId)

    assertListItem(item, "withdrawal", "from", account.accountId)
  }

  /** Creates [count] deposits one after another, so their `started_at` strictly increases. */
  private fun createDeposits(account: TestAccount, count: Int): List<String> =
    (1..count).map { createDeposit(account) }

  @Test
  fun `test sep24 GET transactions honors limit exactly`() {
    val account = newAccount()
    val created = createDeposits(account, 3)

    val ids = listRaw(account, mapOf("asset_code" to "USDC", "limit" to "1")).ids()

    assertEquals(listOf(created.last()), ids)
  }

  @Test
  fun `test sep24 GET transactions are ordered by started_at descending`() {
    val account = newAccount()
    val created = createDeposits(account, 3)

    val ids = listRaw(account, mapOf("asset_code" to "USDC")).ids()

    assertEquals(3, ids.size) {
      "expected all 3 fixture deposits before comparing their order, got $ids"
    }
    assertEquals(created.reversed(), ids)
  }

  @Test
  fun `test sep24 GET transactions no_older_than excludes the boundary`() {
    val account = newAccount()
    val created = createDeposits(account, 3)
    val oldest = listRaw(account, mapOf("asset_code" to "USDC")).item(created.first())

    val ids =
      listRaw(
          account,
          mapOf("asset_code" to "USDC", "no_older_than" to oldest.string("started_at")),
        )
        .ids()

    assertEquals(created.drop(1).toSet(), ids.toSet())
    assertEquals(2, ids.size)
  }

  @Test
  fun `test sep24 GET transactions kind=withdrawal returns only withdrawals`() {
    val account = newAccount()
    val depositId = createDeposit(account)
    val withdrawalId = createWithdrawal(account)

    val list = listRaw(account, mapOf("asset_code" to "USDC", "kind" to "withdrawal"))

    list.item(withdrawalId)
    assertFalse(list.ids().contains(depositId)) {
      "expected kind=withdrawal to exclude the deposit ($depositId)"
    }
  }

  @Test
  fun `test sep24 GET transactions kind=deposit returns only deposits`() {
    val account = newAccount()
    val depositId = createDeposit(account)
    val withdrawalId = createWithdrawal(account)

    val list = listRaw(account, mapOf("asset_code" to "USDC", "kind" to "deposit"))

    list.item(depositId)
    assertFalse(list.ids().contains(withdrawalId)) {
      "expected kind=deposit to exclude the withdrawal ($withdrawalId)"
    }
  }

  @Test
  fun `test sep24 GET transactions pages from the caller's own paging_id`() {
    val account = newAccount()
    val created = createDeposits(account, 3)

    val ids = listRaw(account, mapOf("asset_code" to "USDC", "paging_id" to created.last())).ids()

    assertEquals(created.dropLast(1).toSet(), ids.toSet())
    assertEquals(2, ids.size)
    assertFalse(ids.contains(created.last())) { "the paging transaction itself must not be listed" }
  }

  /**
   * One account holding a withdrawal stored with issuer GDQO and a deposit stored with no issuer
   * (the wallet omitted `asset_issuer`, so the row keeps null even though AP resolved one).
   */
  private class MixedIssuerAccount(
    val account: TestAccount,
    val withdrawalId: String,
    val depositId: String,
  )

  private fun mixedIssuerAccount(): MixedIssuerAccount {
    val account = newAccount()
    return MixedIssuerAccount(
      account,
      createWithdrawal(account),
      createDeposit(account, issuer = null),
    )
  }

  private fun listIds(account: TestAccount, assetCode: String): List<String> =
    listRaw(account, mapOf("asset_code" to assetCode)).ids()

  @Test
  fun `test sep24 GET transactions stellar asset with the owning issuer includes the withdrawal`() {
    val fixture = mixedIssuerAccount()

    val list = listRaw(fixture.account, mapOf("asset_code" to "stellar:USDC:$USDC_GDQO_ISSUER"))

    list.item(fixture.withdrawalId)
  }

  @Test
  fun `test sep24 GET transactions stellar asset with another issuer excludes the withdrawal`() {
    val fixture = mixedIssuerAccount()
    listRaw(fixture.account, mapOf("asset_code" to "USDC")).item(fixture.withdrawalId)

    val ids = listIds(fixture.account, "stellar:USDC:$USDC_GBBD_ISSUER")

    assertFalse(ids.contains(fixture.withdrawalId)) {
      "a USDC/GDQO withdrawal must not be listed under the GBBD issuer"
    }
  }

  @Test
  fun `test sep24 GET transactions stellar asset without an issuer equals the bare code`() {
    val fixture = mixedIssuerAccount()

    val bare = listIds(fixture.account, "USDC")
    val stellarForm = listIds(fixture.account, "stellar:USDC")

    assertEquals(setOf(fixture.withdrawalId, fixture.depositId), bare.toSet()) {
      "the bare code must list both fixture transactions, or the comparison below is vacuous"
    }
    assertEquals(bare.toSet(), stellarForm.toSet())
  }

  @Test
  fun `test sep24 GET transactions bare asset code lists every issuer`() {
    val fixture = mixedIssuerAccount()
    val gbbdDepositId = createDeposit(fixture.account, USDC_GBBD_ISSUER)

    val ids = listIds(fixture.account, "USDC")

    assertEquals(setOf(fixture.withdrawalId, fixture.depositId, gbbdDepositId), ids.toSet())
  }

  @Test
  fun `test sep24 GET transactions stellar asset includes a deposit created without an issuer`() {
    val fixture = mixedIssuerAccount()

    listOf(USDC_GDQO_ISSUER, USDC_GBBD_ISSUER).forEach { issuer ->
      val list = listRaw(fixture.account, mapOf("asset_code" to "stellar:USDC:$issuer"))
      list.item(fixture.depositId)
    }
  }

  @Test
  fun `test sep24 GET transactions rejects request without JWT`() {
    assertThrows<SepNotAuthorizedException> {
      noAuthSep24Client.getTransactions(mapOf("asset_code" to "USDC"))
    }
  }

  @Test
  fun `test sep24 GET transactions rejects unsupported asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.getTransactions(mapOf("asset_code" to "NOT_SUPPORTED"))
      }
    assertEquals("asset code not supported", errorMessage(ex))
  }

  @Test
  fun `test sep24 GET transactions rejects request without asset_code`() {
    val (status, body) = listResponse(newAccount(), mapOf())

    assertEquals(400, status) { "expected 400 but got $status: $body" }
    assertEquals(
      "The \"asset_code\" parameter is missing.",
      JsonParser.parseString(body).asJsonObject.get("error").asString,
    )
  }

  @Test
  fun `test sep24 GET transactions rejects an invalid no_older_than`() {
    val ex =
      assertThrows<SepValidationException> {
        sep24Client.getTransactions(mapOf("asset_code" to "USDC", "no_older_than" to "not-a-date"))
      }
    assertEquals("invalid no_older_than field: not-a-date", errorMessage(ex))
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
  fun `test sep24 TOML has a valid transfer server URL`() {
    // No fallback to TRANSFER_SERVER: stellar-anchor-tests accepts that for anchors that omit the
    // SEP-24 key, but AP's own TOML sets it, so a fallback would hide its removal.
    val transferServer = toml.getString("TRANSFER_SERVER_SEP0024")

    assertTrue(!transferServer.isNullOrBlank()) {
      "stellar.toml must carry a non-blank TRANSFER_SERVER_SEP0024"
    }
    val uri = URI(transferServer)
    assertTrue(uri.isAbsolute) {
      "TRANSFER_SERVER_SEP0024 must be an absolute URL: $transferServer"
    }
    val localHttpHosts = setOf("localhost", "host.docker.internal")
    assertTrue(uri.scheme == "https" || (uri.scheme == "http" && uri.host in localHttpHosts)) {
      "TRANSFER_SERVER_SEP0024 must use https, or http only on $localHttpHosts: $transferServer"
    }
    assertFalse(transferServer.endsWith("/")) {
      "TRANSFER_SERVER_SEP0024 must not end with '/': $transferServer"
    }
  }

  /**
   * Reads `GET /transaction?id=` as the raw `transaction` object. Gson silently nulls absent fields
   * and drops unknown ones, so a parsed object cannot back a schema assertion.
   */
  private fun getTransactionRaw(account: TestAccount, id: String): JsonObject {
    val url =
      "${toml.getString("TRANSFER_SERVER_SEP0024")}/transaction"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("id", id)
        .build()
    val request =
      Request.Builder().url(url).header("Authorization", "Bearer ${account.jwt}").get().build()
    http.newCall(request).execute().use { response ->
      val body = response.body?.string()
      assertEquals(200, response.code) {
        "GET /transaction?id=$id answered ${response.code}: $body"
      }
      return JsonParser.parseString(body).asJsonObject.getAsJsonObject("transaction")
    }
  }

  /**
   * Sends [rpcJson] for [txId] and checks every item of the batch answered without an error, so a
   * rejected setup fails here with the RPC's own message instead of as a puzzling later assertion.
   */
  private fun sendRpc(rpcJson: String, txId: String) {
    val requests: List<RpcRequest> =
      gson.fromJson(
        rpcJson.replace("%TX_ID%", txId),
        object : TypeToken<List<RpcRequest>>() {}.type,
      )
    platformApiClient.sendRpcRequest(requests).use { response ->
      val body = response.body?.string()
      assertTrue(response.isSuccessful) { "RPC setup failed with HTTP ${response.code}: $body" }
      val responses: List<RpcResponse> =
        gson.fromJson(body, object : TypeToken<List<RpcResponse>>() {}.type)
      responses.forEachIndexed { index, rpcResponse ->
        assertNull(rpcResponse.error) {
          "RPC batch item $index failed: ${rpcResponse.error}, body: $body"
        }
      }
    }
  }

  /**
   * A SEP-38 quote of the `sep24` context for [account]. Quotes are single-use, so every test makes
   * its own. Deposit: USD to USDC. Withdrawal: USDC to USD.
   */
  private fun postQuote(account: TestAccount, sellAsset: String, buyAsset: String): String =
    Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), account.jwt)
      .postQuote(sellAsset, QUOTE_AMOUNT, buyAsset, Sep38Context.SEP24)
      .id

  private fun postDepositQuote(account: TestAccount) =
    postQuote(account, USD, "stellar:USDC:$USDC_GDQO_ISSUER")

  private fun postWithdrawalQuote(account: TestAccount) =
    postQuote(account, "stellar:USDC:$USDC_GDQO_ISSUER", USD)

  private fun depositRequest(quoteId: String, overrides: Map<String, String> = mapOf()) =
    mapOf(
      "asset_code" to "USDC",
      "asset_issuer" to USDC_GDQO_ISSUER,
      "source_asset" to USD,
      "amount" to QUOTE_AMOUNT,
      "quote_id" to quoteId,
    ) + overrides

  private fun withdrawalRequest(quoteId: String, overrides: Map<String, String> = mapOf()) =
    mapOf(
      "asset_code" to "USDC",
      "asset_issuer" to USDC_GDQO_ISSUER,
      "destination_asset" to USD,
      "amount" to QUOTE_AMOUNT,
      "quote_id" to quoteId,
    ) + overrides

  // SEP24IF-08
  @Test
  fun `test sep24 deposit with a matching quote is accepted and keeps its quote_id`() {
    val account = newAccount()
    val quoteId = postDepositQuote(account)

    val deposit = account.client.deposit(depositRequest(quoteId))

    assertEquals(quoteId, getTransactionRaw(account, deposit.id).string("quote_id"))
  }

  // SEP24IF-09
  @Test
  fun `test sep24 withdrawal with a matching quote is accepted and keeps its quote_id`() {
    val account = newAccount()
    val quoteId = postWithdrawalQuote(account)

    val withdrawal = account.client.withdraw(withdrawalRequest(quoteId))

    assertEquals(quoteId, getTransactionRaw(account, withdrawal.id).string("quote_id"))
  }

  // SEP24IF-10
  @Test
  fun `test sep24 deposit and withdrawal reject an unknown quote_id`() {
    val account = newAccount()

    val deposit =
      assertThrows<SepValidationException> {
        account.client.deposit(depositRequest("not-a-real-quote-id"))
      }
    assertEquals("Quote not found", errorMessage(deposit))

    val withdrawal =
      assertThrows<SepValidationException> {
        account.client.withdraw(withdrawalRequest("not-a-real-quote-id"))
      }
    assertEquals("Quote not found", errorMessage(withdrawal))
  }

  // SEP24IF-11, SEP24IF-17
  @Test
  fun `test sep24 deposit rejects a source_asset that conflicts with its quote`() {
    val account = newAccount()
    val quoteId = postDepositQuote(account)

    val ex =
      assertThrows<SepValidationException> {
        account.client.deposit(depositRequest(quoteId, mapOf("source_asset" to "iso4217:CAD")))
      }

    assertEquals(
      "source asset(iso4217:CAD) does not match quote sell asset($USD)",
      errorMessage(ex)
    )
    // The rejected request did not bind the quote: the same quote is accepted with matching values.
    assertNotNull(account.client.deposit(depositRequest(quoteId)).id)
  }

  // SEP24IF-12, SEP24IF-17
  @Test
  fun `test sep24 deposit rejects an asset that conflicts with its quote`() {
    val account = newAccount()
    val quoteId = postDepositQuote(account)

    val ex =
      assertThrows<SepValidationException> {
        account.client.deposit(depositRequest(quoteId, mapOf("asset_issuer" to USDC_GBBD_ISSUER)))
      }

    assertEquals(
      "destination asset(stellar:USDC:$USDC_GBBD_ISSUER) does not match quote buy asset(stellar:USDC:$USDC_GDQO_ISSUER)",
      errorMessage(ex),
    )
    assertNotNull(account.client.deposit(depositRequest(quoteId)).id)
  }

  // SEP24IF-15, SEP24IF-17
  @Test
  fun `test sep24 deposit rejects an amount that conflicts with its quote`() {
    val account = newAccount()
    val quoteId = postDepositQuote(account)

    val ex =
      assertThrows<SepValidationException> {
        account.client.deposit(depositRequest(quoteId, mapOf("amount" to "4")))
      }

    assertEquals("amount(4) does not match quote sell amount($QUOTE_AMOUNT)", errorMessage(ex))
    assertNotNull(account.client.deposit(depositRequest(quoteId)).id)
  }

  // SEP24IF-13, SEP24IF-17
  @Test
  fun `test sep24 withdrawal rejects an asset that conflicts with its quote`() {
    val account = newAccount()
    val quoteId = postWithdrawalQuote(account)

    val ex =
      assertThrows<SepValidationException> {
        account.client.withdraw(
          withdrawalRequest(quoteId, mapOf("asset_issuer" to USDC_GBBD_ISSUER))
        )
      }

    assertEquals(
      "source asset(stellar:USDC:$USDC_GBBD_ISSUER) does not match quote sell asset(stellar:USDC:$USDC_GDQO_ISSUER)",
      errorMessage(ex),
    )
    // The rejected request did not bind the quote: the same quote is accepted with matching values.
    assertNotNull(account.client.withdraw(withdrawalRequest(quoteId)).id)
  }

  // SEP24IF-14, SEP24IF-17
  @Test
  fun `test sep24 withdrawal rejects a destination_asset that conflicts with its quote`() {
    val account = newAccount()
    val quoteId = postWithdrawalQuote(account)

    val ex =
      assertThrows<SepValidationException> {
        account.client.withdraw(
          withdrawalRequest(quoteId, mapOf("destination_asset" to "iso4217:CAD"))
        )
      }

    assertEquals(
      "destination asset(iso4217:CAD) does not match quote buy asset($USD)",
      errorMessage(ex),
    )
    assertNotNull(account.client.withdraw(withdrawalRequest(quoteId)).id)
  }

  // SEP24IF-15, SEP24IF-17
  @Test
  fun `test sep24 withdrawal rejects an amount that conflicts with its quote`() {
    val account = newAccount()
    val quoteId = postWithdrawalQuote(account)

    val ex =
      assertThrows<SepValidationException> {
        account.client.withdraw(withdrawalRequest(quoteId, mapOf("amount" to "4")))
      }

    assertEquals("amount(4) does not match quote sell amount($QUOTE_AMOUNT)", errorMessage(ex))
    assertNotNull(account.client.withdraw(withdrawalRequest(quoteId)).id)
  }

  // SEP24IF-16
  @Test
  fun `test sep24 deposit rejects a quote already bound to an earlier transaction`() {
    val account = newAccount()
    val quoteId = postDepositQuote(account)
    assertNotNull(account.client.deposit(depositRequest(quoteId)).id)

    val ex =
      assertThrows<SepValidationException> { account.client.deposit(depositRequest(quoteId)) }

    assertEquals("quote(id=$quoteId) has already been used", errorMessage(ex))
  }

  // SEP24IF-07: AP never sends a deposit as a claimable balance, so asking for one is accepted
  // (the flag is optional) and the transaction carries no claimable_balance_id.
  @Test
  fun `test sep24 deposit asking for a claimable balance gets none`() {
    val account = newAccount()
    val deposit =
      account.client.deposit(
        mapOf(
          "asset_code" to "USDC",
          "asset_issuer" to USDC_GDQO_ISSUER,
          "amount" to "1",
          "claimable_balance_supported" to "true",
        )
      )

    val txn = getTransactionRaw(account, deposit.id)

    assertEquals("incomplete", txn.string("status"))
    val claimableBalanceId = txn.get("claimable_balance_id")
    assertTrue(claimableBalanceId == null || claimableBalanceId.isJsonNull) {
      "claimable_balance_id must be absent or null, got $claimableBalanceId"
    }
  }

  /**
   * Sends [rpcJson] for [txId] and returns the responses without asserting success, for a request
   * the platform is expected to refuse.
   */
  private fun sendRpcForResponses(rpcJson: String, txId: String): List<RpcResponse> {
    val requests: List<RpcRequest> =
      gson.fromJson(
        rpcJson.replace("%TX_ID%", txId),
        object : TypeToken<List<RpcRequest>>() {}.type,
      )
    platformApiClient.sendRpcRequest(requests).use { response ->
      val body = response.body?.string()
      return gson.fromJson(body, object : TypeToken<List<RpcResponse>>() {}.type)
    }
  }

  /**
   * A fresh deposit held for review: `incomplete` to `pending_user_transfer_start` to `on_hold`.
   */
  private fun createOnHoldDeposit(account: TestAccount, message: String): String {
    val depositId = createDeposit(account)
    sendRpc(SEP24_ON_HOLD_RPC.replace("%HOLD_MESSAGE%", message), depositId)
    return depositId
  }

  // SEP24IF-20, SEP24IF-21
  @Test
  fun `test sep24 GET transaction reports on_hold and resumes when funds are received`() {
    val account = newAccount()
    val message = "held for review ${UUID.randomUUID()}"
    val depositId = createOnHoldDeposit(account, message)

    val held = getTransactionRaw(account, depositId)

    assertEquals("on_hold", held.string("status"))
    assertEquals(message, held.string("message"))
    assertSep24TransactionSchema(held, Sep24SchemaCase.DEPOSIT_PENDING)

    sendRpc(SEP24_FUNDS_RECEIVED_RPC, depositId)

    assertEquals("pending_anchor", getTransactionRaw(account, depositId).string("status"))
  }

  // SEP24IF-22
  @Test
  fun `test sep24 GET transaction reports an error with its message`() {
    val account = newAccount()
    val depositId = createDeposit(account)
    val message = "bank rejected the transfer ${UUID.randomUUID()}"
    sendRpc(SEP24_ERROR_RPC.replace("%ERROR_MESSAGE%", message), depositId)

    val txn = getTransactionRaw(account, depositId)

    assertEquals("error", txn.string("status"))
    assertEquals(message, txn.string("message"))
    assertSep24TransactionSchema(txn, Sep24SchemaCase.DEPOSIT_INCOMPLETE)
  }

  // SEP24IF-23
  @Test
  fun `test sep24 GET transaction reports an expired withdrawal with its message`() {
    val account = newAccount()
    val withdrawalId = createWithdrawal(account)
    val message = "abandoned by the user ${UUID.randomUUID()}"
    sendRpc(SEP24_EXPIRE_RPC.replace("%EXPIRE_MESSAGE%", message), withdrawalId)

    val txn = getTransactionRaw(account, withdrawalId)

    assertEquals("expired", txn.string("status"))
    assertEquals(message, txn.string("message"))
    assertSep24TransactionSchema(txn, Sep24SchemaCase.WITHDRAWAL_INCOMPLETE)
  }

  /**
   * An amount as a number at SEP-24's maximum scale of 7, so `50` and `50.0000000` are the same
   * amount and a failure prints it legibly.
   */
  private fun number(value: String): java.math.BigDecimal = java.math.BigDecimal(value).setScale(7)

  private fun JsonObject.numberAt(field: String): java.math.BigDecimal = number(string(field))

  // SEP24IF-25, SEP24IF-26, SEP24IF-27: SEP-24 "Amount Formulas".
  @Test
  fun `test sep24 GET transaction keeps the amount formulas for a refunded deposit`() {
    val account = newAccount()
    val depositId = createDeposit(account)
    sendRpc(SEP24_TWO_REFUND_PAYMENTS_RPC, depositId)

    val txn = getTransactionRaw(account, depositId)

    // The amounts the RPCs sent. SEP-24 deprecates amount_fee in favor of fee_details, and AP only
    // returns fee_details.
    assertEquals(number("100"), txn.numberAt("amount_in"))
    assertEquals(number("46"), txn.numberAt("amount_out"))
    val fee = txn.getAsJsonObject("fee_details").numberAt("total")
    assertEquals(number("2"), fee)

    val refunds = txn.getAsJsonObject("refunds")
    assertEquals(number("50"), refunds.numberAt("amount_refunded"))
    assertEquals(number("2"), refunds.numberAt("amount_fee"))
    val payments = refunds.getAsJsonArray("payments").map { it.asJsonObject }
    assertEquals(setOf(number("30"), number("20")), payments.map { it.numberAt("amount") }.toSet())
    assertEquals(listOf(number("1"), number("1")), payments.map { it.numberAt("fee") })

    // refunds.amount_refunded = sum(payments[].amount), refunds.amount_fee = sum(payments[].fee)
    assertEquals(
      payments.map { it.numberAt("amount") }.reduce { a, b -> a + b },
      refunds.numberAt("amount_refunded"),
    )
    assertEquals(
      payments.map { it.numberAt("fee") }.reduce { a, b -> a + b },
      refunds.numberAt("amount_fee"),
    )
    // amount_out = amount_in - amount_fee - refunds.amount_refunded - refunds.amount_fee
    assertEquals(
      txn.numberAt("amount_in") -
        fee -
        refunds.numberAt("amount_refunded") -
        refunds.numberAt("amount_fee"),
      txn.numberAt("amount_out"),
    )
  }

  // SEP24IF-24
  @Test
  fun `test sep24 refuses to expire a deposit whose funds were received`() {
    val account = newAccount()
    val depositId = createOnHoldDeposit(account, "held for review ${UUID.randomUUID()}")

    val responses =
      sendRpcForResponses(
        SEP24_EXPIRE_RPC.replace("%EXPIRE_MESSAGE%", "abandoned by the user"),
        depositId,
      )

    assertEquals(1, responses.size)
    assertEquals(
      "RPC method[notify_transaction_expired] is not supported. " +
        "Status[on_hold], kind[deposit], protocol[24], funds received[true]",
      responses.single().error?.message,
    )
    assertEquals("on_hold", getTransactionRaw(account, depositId).string("status"))
  }

  @Test
  fun `test sep24 GET transaction returns a pending deposit in the SEP-24 shape`() {
    val account = newAccount()
    val depositId = createDeposit(account)
    sendRpc(SEP24_PENDING_DEPOSIT_RPC, depositId)

    val txn = getTransactionRaw(account, depositId)

    assertEquals(depositId, txn.string("id"))
    assertEquals("pending_user_transfer_start", txn.string("status"))
    assertEquals("100", txn.string("amount_in"))
    assertEquals("iso4217:USD", txn.string("amount_in_asset"))
    assertEquals("95", txn.string("amount_out"))
    assertEquals("stellar:USDC:$USDC_GDQO_ISSUER", txn.string("amount_out_asset"))
    assertEquals("deposit", txn.string("kind"))
    assertEquals(account.accountId, txn.string("to"))
    assertSep24TransactionSchema(txn, Sep24SchemaCase.DEPOSIT_PENDING)
  }

  @Test
  fun `test sep24 GET transaction returns a pending_user_transfer_start withdrawal in the SEP-24 shape`() {
    val account = newAccount()
    val withdrawalId = createWithdrawal(account)
    sendRpc(SEP24_PENDING_WITHDRAWAL_RPC, withdrawalId)

    val txn = getTransactionRaw(account, withdrawalId)

    assertEquals(withdrawalId, txn.string("id"))
    assertEquals("pending_user_transfer_start", txn.string("status"))
    assertEquals("100", txn.string("amount_in"))
    assertEquals("stellar:USDC:$USDC_GDQO_ISSUER", txn.string("amount_in_asset"))
    assertEquals("95", txn.string("amount_out"))
    assertEquals("iso4217:USD", txn.string("amount_out_asset"))
    assertEquals("withdrawal", txn.string("kind"))
    assertEquals(account.accountId, txn.string("from"))
    val anchorAccount = txn.string("withdraw_anchor_account")
    assertTrue(runCatching { KeyPair.fromAccountId(anchorAccount) }.isSuccess) {
      "withdraw_anchor_account must be a valid Stellar account, got $anchorAccount"
    }
    assertTrue(txn.string("withdraw_memo").isNotEmpty())
    assertEquals("id", txn.string("withdraw_memo_type"))
    assertSep24TransactionSchema(txn, Sep24SchemaCase.WITHDRAWAL_PENDING_USER_TRANSFER_START)
  }

  @Test
  fun `test sep24 more_info_url of a deposit is served as HTML`() {
    val account = newAccount()
    val moreInfoUrl = getTransactionRaw(account, createDeposit(account)).string("more_info_url")
    // The query carries the URL's JWT, so failure messages name only the part before it.
    val shown = moreInfoUrl.substringBefore('?')

    // A plain GET, as a wallet opens it: the URL carries its own token, so no Authorization header.
    http.newCall(Request.Builder().url(moreInfoUrl).get().build()).execute().use { response ->
      assertEquals(200, response.code) { "GET $shown answered ${response.code}" }
      val contentType = response.header("Content-Type")
      assertTrue(contentType != null && contentType.startsWith("text/html")) {
        "expected $shown to be served as text/html but got Content-Type: $contentType"
      }
    }
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

/** The sell amount of every quote and the `amount` of every request that uses one. */
private const val QUOTE_AMOUNT = "5"

private const val USD = "iso4217:USD"

private const val USDC_GDQO_ISSUER = "GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"

/** The second USDC issuer configured with SEP-24 enabled. */
private const val USDC_GBBD_ISSUER = "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"

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

/**
 * Moves a fresh SEP-24 deposit from `incomplete` to `pending_user_transfer_start`. The asset is the
 * issuer the test's deposit was created with. Nothing advances it afterwards: the reference server
 * routes SEP-24 events to a no-op processor.
 */
private const val SEP24_PENDING_DEPOSIT_RPC =
  """
[
  {
    "id": "1",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "pending deposit fixture",
      "amount_in": { "amount": "100", "asset": "iso4217:USD" },
      "amount_out": {
        "amount": "95",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "fee_details": { "total": "5", "asset": "iso4217:USD" },
      "amount_expected": { "amount": "100" }
    }
  }
]
"""

/**
 * Holds a fresh SEP-24 deposit for review. `%HOLD_MESSAGE%` is replaced by the test, so the message
 * read back through `GET /transaction` is one this test chose.
 */
private const val SEP24_ON_HOLD_RPC =
  """
[
  {
    "id": "1",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "pending deposit fixture",
      "amount_in": { "amount": "100", "asset": "iso4217:USD" },
      "amount_out": {
        "amount": "95",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "fee_details": { "total": "5", "asset": "iso4217:USD" },
      "amount_expected": { "amount": "100" }
    }
  },
  {
    "id": "2",
    "method": "notify_transaction_on_hold",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "%HOLD_MESSAGE%"
    }
  }
]
"""

/** The anchor clears the hold: the held deposit's funds are confirmed received. */
private const val SEP24_FUNDS_RECEIVED_RPC =
  """
[
  {
    "id": "1",
    "method": "notify_offchain_funds_received",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "funds received after review",
      "external_transaction_id": "ext-on-hold-fixture",
      "amount_in": { "amount": "100" }
    }
  }
]
"""

/** The anchor gives up on a transaction that has not moved yet. */
private const val SEP24_ERROR_RPC =
  """
[
  {
    "id": "1",
    "method": "notify_transaction_error",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "%ERROR_MESSAGE%"
    }
  }
]
"""

/**
 * A deposit of 100 USD with a fee of 2, paid out as 46 USDC, then refunded in two payments (30 with
 * a fee of 1, and 20 with a fee of 1): 100 - 2 - 50 - 2 = 46.
 */
private const val SEP24_TWO_REFUND_PAYMENTS_RPC =
  """
[
  {
    "id": "1",
    "method": "request_offchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "refund fixture: requesting funds",
      "amount_in": { "amount": "100", "asset": "iso4217:USD" },
      "amount_out": {
        "amount": "46",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "fee_details": { "total": "2", "asset": "iso4217:USD" },
      "amount_expected": { "amount": "100" }
    }
  },
  {
    "id": "2",
    "method": "notify_offchain_funds_received",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "refund fixture: funds received",
      "external_transaction_id": "ext-refund-fixture",
      "amount_in": { "amount": "100" },
      "amount_out": { "amount": "46" },
      "fee_details": { "total": "2", "asset": "iso4217:USD" }
    }
  },
  {
    "id": "3",
    "method": "notify_refund_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "refund fixture: first refund",
      "refund": {
        "id": "refund-1",
        "amount": { "amount": "30", "asset": "iso4217:USD" },
        "amount_fee": { "amount": "1", "asset": "iso4217:USD" }
      }
    }
  },
  {
    "id": "4",
    "method": "notify_refund_sent",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "refund fixture: second refund",
      "refund": {
        "id": "refund-2",
        "amount": { "amount": "20", "asset": "iso4217:USD" },
        "amount_fee": { "amount": "1", "asset": "iso4217:USD" }
      }
    }
  }
]
"""

/** `expired` means the funds never arrived, so the platform refuses it once they have. */
private const val SEP24_EXPIRE_RPC =
  """
[
  {
    "id": "1",
    "method": "notify_transaction_expired",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "%EXPIRE_MESSAGE%"
    }
  }
]
"""

/**
 * Moves a fresh SEP-24 withdrawal from `incomplete` to `pending_user_transfer_start`. It sends no
 * `memo` or `destination_account`: the test profile's SEP-24 deposit info generator is `self`,
 * which fills them in itself and rejects a request that carries them.
 */
private const val SEP24_PENDING_WITHDRAWAL_RPC =
  """
[
  {
    "id": "1",
    "method": "request_onchain_funds",
    "jsonrpc": "2.0",
    "params": {
      "transaction_id": "%TX_ID%",
      "message": "pending withdrawal fixture",
      "amount_in": {
        "amount": "100",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "amount_out": { "amount": "95", "asset": "iso4217:USD" },
      "fee_details": {
        "total": "5",
        "asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP"
      },
      "amount_expected": { "amount": "100" }
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
