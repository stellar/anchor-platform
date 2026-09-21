package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.api.exception.SepNotAuthorizedException
import org.stellar.anchor.api.exception.SepNotFoundException
import org.stellar.anchor.api.exception.SepValidationException
import org.stellar.anchor.api.rpc.RpcRequest
import org.stellar.anchor.api.sep.sep38.Sep38Context
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.auth.AuthHelper
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
  private val platformApiClient =
    PlatformApiClient(AuthHelper.forNone(), config.env["platform.server.url"]!!)

  private fun authenticateWithMemo(keyPair: SigningKeyPair, memoId: ULong): String {
    return runBlocking { anchor.auth().authenticate(keyPair, memoId = memoId) }.token
  }

  private fun authenticateWithoutMemo(keyPair: SigningKeyPair): String {
    return runBlocking { anchor.auth().authenticate(keyPair) }.token
  }

  /**
   * Creates a fresh, isolated keypair, authenticates it, and issues [count] deposits for it -- so
   * count/order/filter assertions run against an account with an exact, deterministic transaction
   * set instead of the shared `sep6Client` wallet used by other tests in this file.
   */
  private fun createAccountWithDeposits(count: Int): Pair<Sep6Client, List<String>> {
    val keyPair = SigningKeyPair(KeyPair.random())
    val jwt = authenticateWithoutMemo(keyPair)
    val client = Sep6Client(toml.getString("TRANSFER_SERVER"), jwt)
    val ids =
      (1..count).map {
        client.deposit(mapOf("asset_code" to "USDC", "amount" to "1", "type" to "SWIFT")).id!!
      }
    return client to ids
  }

  private data class DepositWithdrawFixture(
    val client: Sep6Client,
    val depositId: String,
    val withdrawId: String,
    val depositExchangeId: String,
  )

  /**
   * Creates a fresh, isolated keypair, authenticates it, and issues one deposit, one withdrawal,
   * and one deposit-exchange for it -- so kind-filter assertions run against an account holding
   * `deposit`, `withdrawal`, and `deposit-exchange` with a deterministic id for each, proving the
   * `kind` filter distinguishes `deposit` from the similarly-named `deposit-exchange` rather than
   * matching it as a prefix.
   */
  private fun createAccountWithDepositAndWithdrawal(): DepositWithdrawFixture {
    val keyPair = SigningKeyPair(KeyPair.random())
    val jwt = authenticateWithoutMemo(keyPair)
    val client = Sep6Client(toml.getString("TRANSFER_SERVER"), jwt)
    val depositId =
      client.deposit(mapOf("asset_code" to "USDC", "amount" to "1", "type" to "SWIFT")).id!!
    val withdrawId =
      client.withdraw(mapOf("asset_code" to "USDC", "type" to "bank_account", "amount" to "1")).id!!
    val depositExchangeId =
      client
        .deposit(
          mapOf(
            "destination_asset" to "USDC",
            "source_asset" to "iso4217:USD",
            "amount" to "1",
            "account" to keyPair.address,
            "type" to "SWIFT",
          ),
          exchange = true,
        )
        .id!!
    return DepositWithdrawFixture(client, depositId, withdrawId, depositExchangeId)
  }

  /**
   * Fetches GET /transactions as a raw JSON array, bypassing [Sep6Client.getTransactions]'s parsed
   * response -- Gson silently nulls absent fields on a parsed object, which would hide a missing
   * required field from schema assertions.
   */
  private fun getTransactionsRawArray(client: Sep6Client, query: Map<String, String>): JsonArray {
    val urlBuilder = "${toml.getString("TRANSFER_SERVER")}/transactions".toHttpUrl().newBuilder()
    query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
    val rawJson = client.httpGet(urlBuilder.build().toString(), client.jwt)!!
    return JsonParser.parseString(rawJson).asJsonObject.getAsJsonArray("transactions")
  }

  /**
   * SEP-6's own transaction status enum (sep-0006.md, "Transaction History" -> "`status` should be
   * one of:"). Deliberately NOT `org.stellar.anchor.api.sep.SepTransactionStatus` -- that enum is
   * shared across SEP-6/24/31, so it also accepts SEP-31-only values (`pending_sender`,
   * `pending_receiver`) and matches case-insensitively; a SEP-6 response must use exactly one of
   * these lowercase, snake_case strings.
   */
  private val sep6Statuses =
    setOf(
      "completed",
      "pending_external",
      "pending_anchor",
      "on_hold",
      "pending_stellar",
      "pending_trust",
      "pending_user",
      "pending_user_transfer_start",
      "pending_user_transfer_complete",
      "pending_customer_info_update",
      "pending_transaction_info_update",
      "incomplete",
      "expired",
      "no_market",
      "too_small",
      "too_large",
      "error",
      "refunded",
    )

  // Every other field sep-0006.md's transaction object table defines, by JSON type -- present only
  // to say "if this field is present, it must have this shape"; none of these are required.
  private val sep6OptionalStringFields =
    setOf(
      "more_info_url",
      "amount_in",
      "amount_in_asset",
      "amount_out",
      "amount_out_asset",
      "amount_fee",
      "amount_fee_asset",
      "quote_id",
      "from",
      "to",
      "external_extra",
      "external_extra_text",
      "deposit_memo",
      "deposit_memo_type",
      "withdraw_anchor_account",
      "withdraw_memo",
      "withdraw_memo_type",
      "stellar_transaction_id",
      "external_transaction_id",
      "message",
      "required_info_message",
      "claimable_balance_id",
    )
  private val sep6OptionalInstantFields =
    setOf("updated_at", "completed_at", "user_action_required_by")
  private val sep6OptionalNumberFields = setOf("status_eta")
  private val sep6OptionalBooleanFields = setOf("refunded")
  private val sep6OptionalObjectFields =
    setOf("fee_details", "refunds", "instructions", "required_info_updates")

  private fun assertJsonString(obj: JsonObject, field: String, required: Boolean) {
    val value = obj.get(field)
    if (value == null || value.isJsonNull) {
      Assertions.assertFalse(required) { "expected '$field' to be present and non-null in $obj" }
      return
    }
    Assertions.assertTrue(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
      "expected '$field' to be a JSON string in $obj, was $value"
    }
  }

  private fun assertOptionalInstant(obj: JsonObject, field: String) {
    val value = obj.get(field)
    if (value == null || value.isJsonNull) return
    assertJsonString(obj, field, required = true)
    assertDoesNotThrow({ "expected '$field' ($value) to parse as an ISO-8601 instant" }) {
      Instant.parse(value.asString)
    }
  }

  private fun assertOptionalNumber(obj: JsonObject, field: String) {
    val value = obj.get(field)
    if (value == null || value.isJsonNull) return
    Assertions.assertTrue(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
      "expected '$field' to be a JSON number in $obj, was $value"
    }
  }

  private fun assertOptionalBoolean(obj: JsonObject, field: String) {
    val value = obj.get(field)
    if (value == null || value.isJsonNull) return
    Assertions.assertTrue(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
      "expected '$field' to be a JSON boolean in $obj, was $value"
    }
  }

  /**
   * `Amount` objects (`org.stellar.anchor.api.shared.Amount`) are `{amount, asset}` -- used
   * wherever sep-0006.md's schema text describes a plain amount *string* for a refund-related field
   * (`refunds.amount_refunded`/`amount_fee`, `refunds.payments[].amount`/`fee`). Confirmed against
   * `Refunds`/`RefundPayment`/`Amount` (api-schema) and the real fixture in
   * `Sep6TransactionUtilsTest.kt`, not assumed from the spec text alone.
   */
  private fun assertAmountObject(obj: JsonObject, field: String, required: Boolean) {
    val value = obj.get(field)
    if (value == null || value.isJsonNull) {
      Assertions.assertFalse(required) { "expected '$field' to be present and non-null in $obj" }
      return
    }
    Assertions.assertTrue(value.isJsonObject) {
      "expected '$field' to be a JSON object ({amount, asset}) in $obj, was $value"
    }
    val amountObj = value.asJsonObject
    assertJsonString(amountObj, "amount", required = true)
    assertJsonString(amountObj, "asset", required = false)
    val unknown = amountObj.keySet() - setOf("amount", "asset")
    Assertions.assertTrue(unknown.isEmpty()) {
      "unexpected field(s) $unknown in '$field': $amountObj"
    }
  }

  /** Fee Details Object Schema (sep-0006.md), incl. Fee Details Details Object Schema. */
  private fun assertValidFeeDetails(feeDetails: JsonObject) {
    assertJsonString(feeDetails, "total", required = true)
    assertJsonString(feeDetails, "asset", required = true)
    feeDetails
      .get("details")
      ?.takeIf { !it.isJsonNull }
      ?.let { details ->
        Assertions.assertTrue(details.isJsonArray) {
          "expected 'fee_details.details' to be a JSON array, was $details"
        }
        details.asJsonArray.forEach { entry ->
          Assertions.assertTrue(entry.isJsonObject) {
            "expected each 'fee_details.details' entry to be a JSON object, was $entry"
          }
          val detail = entry.asJsonObject
          assertJsonString(detail, "name", required = true)
          assertJsonString(detail, "amount", required = true)
          // `FeeDescription.description` (api-schema) is a real, optional field -- not in
          // sep-0006.md's own "Fee Details Details Object Schema" table, but present in this
          // codebase's actual response shape, so it must be accepted, not rejected as unknown.
          assertJsonString(detail, "description", required = false)
          val unknownDetailFields = detail.keySet() - setOf("name", "amount", "description")
          Assertions.assertTrue(unknownDetailFields.isEmpty()) {
            "unexpected field(s) $unknownDetailFields in a 'fee_details.details' entry: $detail"
          }
        }
      }
    val unknown = feeDetails.keySet() - setOf("total", "asset", "details")
    Assertions.assertTrue(unknown.isEmpty()) {
      "unexpected field(s) $unknown in 'fee_details': $feeDetails"
    }
  }

  /** Refunds Object Schema + Refund Payment Object Schema (sep-0006.md). */
  private fun assertValidRefunds(refunds: JsonObject) {
    assertAmountObject(refunds, "amount_refunded", required = true)
    assertAmountObject(refunds, "amount_fee", required = true)
    val payments = refunds.get("payments")
    Assertions.assertTrue(payments != null && !payments.isJsonNull && payments.isJsonArray) {
      "expected 'refunds.payments' to be a JSON array in $refunds"
    }
    payments!!.asJsonArray.forEach { entry ->
      Assertions.assertTrue(entry.isJsonObject) {
        "expected each 'refunds.payments' entry to be a JSON object, was $entry"
      }
      val payment = entry.asJsonObject
      assertJsonString(payment, "id", required = true)
      assertJsonString(payment, "id_type", required = true)
      assertAmountObject(payment, "amount", required = true)
      assertAmountObject(payment, "fee", required = true)
      Assertions.assertTrue(payment.get("id_type").asString in setOf("stellar", "external")) {
        "expected 'refunds.payments[].id_type' to be 'stellar' or 'external', was ${payment.get("id_type")}"
      }
      // `RefundPayment` (api-schema) also carries `requested_at`/`refunded_at` -- not in
      // sep-0006.md's own Refund Payment Object Schema table, but a real, optional part of this
      // codebase's actual response shape, so they must be accepted, not rejected as unknown.
      assertOptionalInstant(payment, "requested_at")
      assertOptionalInstant(payment, "refunded_at")
      val unknownPaymentFields =
        payment.keySet() - setOf("id", "id_type", "amount", "fee", "requested_at", "refunded_at")
      Assertions.assertTrue(unknownPaymentFields.isEmpty()) {
        "unexpected field(s) $unknownPaymentFields in a 'refunds.payments' entry: $payment"
      }
    }
    val unknown = refunds.keySet() - setOf("amount_refunded", "amount_fee", "payments")
    Assertions.assertTrue(unknown.isEmpty()) {
      "unexpected field(s) $unknown in 'refunds': $refunds"
    }
  }

  /**
   * `instructions` is a map of SEP-9 financial-account-field name -> `{value, description}` (SEP-9
   * financial account fields section). Field names are caller/anchor-defined, so only the shape of
   * each entry is checked, not a fixed key set.
   */
  private fun assertValidInstructions(instructions: JsonObject) {
    instructions.entrySet().forEach { (fieldName, value) ->
      Assertions.assertTrue(value.isJsonObject) {
        "expected instructions.'$fieldName' to be a JSON object, was $value"
      }
      val field = value.asJsonObject
      assertJsonString(field, "value", required = true)
      assertJsonString(field, "description", required = false)
      val unknown = field.keySet() - setOf("value", "description")
      Assertions.assertTrue(unknown.isEmpty()) {
        "unexpected field(s) $unknown in instructions.'$fieldName': $field"
      }
    }
  }

  /**
   * `required_info_updates` is documented in sep-0006.md as "described in the same format as /info"
   * -- a map of field name -> `{description, choices, optional}`, the same `AssetInfo.Field` shape
   * `/info`'s own fields use. Field names are caller/anchor-defined, so only the shape of each
   * entry is checked, not a fixed key set. `description` is treated as required, matching every
   * real per-field entry this codebase's response actually produces (a humanized version of the
   * field name, since SEP-6 has no richer per-field metadata store); `choices` and `optional` are
   * optional/defaultable per `AssetInfo.Field`.
   */
  private fun assertValidRequiredInfoUpdates(requiredInfoUpdates: JsonObject) {
    requiredInfoUpdates.entrySet().forEach { (fieldName, value) ->
      Assertions.assertTrue(value.isJsonObject) {
        "expected required_info_updates.'$fieldName' to be a JSON object, was $value"
      }
      val field = value.asJsonObject
      assertJsonString(field, "description", required = true)
      field
        .get("choices")
        ?.takeIf { !it.isJsonNull }
        ?.let { choices ->
          Assertions.assertTrue(choices.isJsonArray) {
            "expected required_info_updates.'$fieldName'.choices to be a JSON array, was $choices"
          }
          choices.asJsonArray.forEach { entry ->
            Assertions.assertTrue(entry.isJsonPrimitive && entry.asJsonPrimitive.isString) {
              "expected each entry of required_info_updates.'$fieldName'.choices to be a JSON string, was $entry"
            }
          }
        }
      assertOptionalBoolean(field, "optional")
      val unknown = field.keySet() - setOf("description", "choices", "optional")
      Assertions.assertTrue(unknown.isEmpty()) {
        "unexpected field(s) $unknown in required_info_updates.'$fieldName': $field"
      }
    }
  }

  /**
   * Validates a single SEP-6 transaction object from raw JSON, exhaustively, against every field
   * `sep-0006.md`'s "Transaction History" response schema defines: `id`/`kind`/`status` plus a
   * caller-specified extra field (`to` for deposit, `from` for withdrawal) are required; every
   * other documented field is checked for the correct JSON shape *if present*; and any field name
   * not in the schema at all fails the assertion, so a stray SEP-24/31-only field can't slip
   * through unnoticed.
   */
  private fun assertValidSep6TransactionSchema(txn: JsonObject, requiredField: String) {
    listOf("id", "kind", requiredField).forEach { field -> assertJsonString(txn, field, true) }

    assertJsonString(txn, "status", required = true)
    val status = txn.get("status").asString
    Assertions.assertTrue(sep6Statuses.contains(status)) {
      "expected 'status' ($status) to be one of SEP-6's defined status values: $sep6Statuses"
    }

    // `started_at` is optional per the spec's general schema, but every code path exercised by this
    // test suite always populates it -- so it's required here, as a stricter check on our own
    // reference server's actual behavior, not a spec violation.
    assertJsonString(txn, "started_at", required = true)
    assertDoesNotThrow({
      "expected 'started_at' (${txn.get("started_at")}) to parse as an ISO-8601 instant"
    }) {
      Instant.parse(txn.get("started_at").asString)
    }

    sep6OptionalStringFields.forEach { field -> assertJsonString(txn, field, required = false) }
    sep6OptionalInstantFields.forEach { field -> assertOptionalInstant(txn, field) }
    sep6OptionalNumberFields.forEach { field -> assertOptionalNumber(txn, field) }
    sep6OptionalBooleanFields.forEach { field -> assertOptionalBoolean(txn, field) }

    txn
      .get("fee_details")
      ?.takeIf { !it.isJsonNull }
      ?.let { assertValidFeeDetails(it.asJsonObject) }
    txn.get("refunds")?.takeIf { !it.isJsonNull }?.let { assertValidRefunds(it.asJsonObject) }
    txn
      .get("instructions")
      ?.takeIf { !it.isJsonNull }
      ?.let { assertValidInstructions(it.asJsonObject) }
    txn
      .get("required_info_updates")
      ?.takeIf { !it.isJsonNull }
      ?.let { assertValidRequiredInfoUpdates(it.asJsonObject) }

    val knownFields =
      setOf("id", "kind", "status", "started_at", requiredField) +
        sep6OptionalStringFields +
        sep6OptionalInstantFields +
        sep6OptionalNumberFields +
        sep6OptionalBooleanFields +
        sep6OptionalObjectFields
    val unknownFields = txn.keySet() - knownFields
    Assertions.assertTrue(unknownFields.isEmpty()) {
      "unexpected field(s) $unknownFields not defined by the SEP-6 transaction schema in $txn"
    }
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
  fun `test sep6 GET transactions rejects account param that does not match the JWT`() {
    assertThrows<SepNotAuthorizedException> {
      sep6Client.getTransactions(
        mapOf("asset_code" to "USDC", "account" to KeyPair.random().accountId)
      )
    }
  }

  @Test
  fun `test sep6 GET transactions with account omitted stays scoped to the JWT's own memo on a shared account`() {
    val sharedKeyPair = SigningKeyPair(KeyPair.random())
    val memoAJwt = authenticateWithMemo(sharedKeyPair, 111UL)
    val memoBJwt = authenticateWithMemo(sharedKeyPair, 222UL)

    val memoAClient = Sep6Client(toml.getString("TRANSFER_SERVER"), memoAJwt)
    val memoBClient = Sep6Client(toml.getString("TRANSFER_SERVER"), memoBJwt)

    fun depositRequest() =
      mapOf(
        "asset_code" to "USDC",
        "account" to sharedKeyPair.address,
        "amount" to "1",
        "type" to "SWIFT",
      )

    val memoATxnId = memoAClient.deposit(depositRequest()).id!!
    val memoBTxnId = memoBClient.deposit(depositRequest()).id!!

    val listedIds =
      memoAClient.getTransactions(mapOf("asset_code" to "USDC")).transactions.map { it.id }

    Assertions.assertTrue(listedIds.contains(memoATxnId)) {
      "expected memo A's own transaction to be visible when account is omitted"
    }
    Assertions.assertFalse(listedIds.contains(memoBTxnId)) {
      "GET /transactions leaked memo B's transaction ($memoBTxnId) to memo A's JWT when account was omitted"
    }
  }

  @Test
  fun `test sep6 deposit appears in transactions listing with valid schema`() {
    val depositId =
      sep6Client
        .deposit(
          mapOf(
            "asset_code" to "USDC",
            "account" to clientWalletAccount,
            "amount" to "1",
            "type" to "SWIFT",
          )
        )
        .id!!

    val transactions =
      getTransactionsRawArray(
        sep6Client,
        mapOf("asset_code" to "USDC", "account" to clientWalletAccount),
      )
    val depositTxn =
      transactions.map { it.asJsonObject }.find { it.get("id").asString == depositId }

    Assertions.assertNotNull(depositTxn) {
      "expected deposit ($depositId) to be present in the transactions listing"
    }
    assertValidSep6TransactionSchema(depositTxn!!, "to")
  }

  @Test
  fun `test sep6 withdrawal appears in transactions listing with valid schema`() {
    val withdrawId =
      sep6Client
        .withdraw(mapOf("asset_code" to "USDC", "type" to "bank_account", "amount" to "1"))
        .id!!

    val transactions =
      getTransactionsRawArray(
        sep6Client,
        mapOf("asset_code" to "USDC", "account" to clientWalletAccount),
      )
    val withdrawTxn =
      transactions.map { it.asJsonObject }.find { it.get("id").asString == withdrawId }

    Assertions.assertNotNull(withdrawTxn) {
      "expected withdrawal ($withdrawId) to be present in the transactions listing"
    }
    assertValidSep6TransactionSchema(withdrawTxn!!, "from")
  }

  @Test
  fun `test sep6 GET transactions returns empty list for account with no history`() {
    val freshKeyPair = SigningKeyPair(KeyPair.random())
    val freshJwt = authenticateWithoutMemo(freshKeyPair)
    val freshClient = Sep6Client(toml.getString("TRANSFER_SERVER"), freshJwt)

    val response = freshClient.getTransactions(mapOf("asset_code" to "USDC"))

    Assertions.assertNotNull(response.transactions)
    Assertions.assertEquals(0, response.transactions.size)
  }

  @Test
  fun `test sep6 GET transactions honors limit parameter exactly`() {
    val (client, _) = createAccountWithDeposits(3)

    val response = client.getTransactions(mapOf("asset_code" to "USDC", "limit" to "1"))

    Assertions.assertEquals(1, response.transactions.size)
  }

  @Test
  fun `test sep6 GET transactions are ordered by started_at descending`() {
    val (client, depositIds) = createAccountWithDeposits(3)

    val startedAts =
      client.getTransactions(mapOf("asset_code" to "USDC")).transactions.map {
        Instant.parse(it.startedAt)
      }

    Assertions.assertEquals(depositIds.size, startedAts.size) {
      "expected all ${depositIds.size} fixture deposits to be returned before checking their order" +
        " -- got ${startedAts.size}, which would make the pairwise check below vacuous"
    }

    for (i in 0 until startedAts.size - 1) {
      Assertions.assertTrue(startedAts[i] >= startedAts[i + 1]) {
        "expected started_at[$i] (${startedAts[i]}) to be >= started_at[${i + 1}] (${startedAts[i + 1]})"
      }
    }
  }

  @Test
  fun `test sep6 GET transactions no_older_than filters strictly newer records`() {
    val (client, _) = createAccountWithDeposits(3)

    val transactions = client.getTransactions(mapOf("asset_code" to "USDC")).transactions
    val oldest = transactions.minByOrNull { Instant.parse(it.startedAt) }!!

    val response =
      client.getTransactions(mapOf("asset_code" to "USDC", "no_older_than" to oldest.startedAt))
    val ids = response.transactions.map { it.id }

    Assertions.assertEquals(2, ids.size)
    Assertions.assertFalse(ids.contains(oldest.id)) {
      "expected the boundary record (${oldest.id}) to be excluded by no_older_than"
    }
  }

  @Test
  fun `test sep6 GET transactions kind=deposit returns only the deposit`() {
    val (client, depositId, withdrawId, depositExchangeId) = createAccountWithDepositAndWithdrawal()

    val ids =
      client.getTransactions(mapOf("asset_code" to "USDC", "kind" to "deposit")).transactions.map {
        it.id
      }

    Assertions.assertTrue(ids.contains(depositId)) {
      "expected kind=deposit to return the deposit ($depositId)"
    }
    Assertions.assertFalse(ids.contains(withdrawId)) {
      "expected kind=deposit to exclude the withdrawal ($withdrawId)"
    }
    Assertions.assertFalse(ids.contains(depositExchangeId)) {
      "expected kind=deposit to exclude the deposit-exchange ($depositExchangeId)"
    }
  }

  @Test
  fun `test sep6 GET transactions kind=withdrawal returns only the withdrawal`() {
    val (client, depositId, withdrawId, depositExchangeId) = createAccountWithDepositAndWithdrawal()

    val ids =
      client
        .getTransactions(mapOf("asset_code" to "USDC", "kind" to "withdrawal"))
        .transactions
        .map { it.id }

    Assertions.assertTrue(ids.contains(withdrawId)) {
      "expected kind=withdrawal to return the withdrawal ($withdrawId)"
    }
    Assertions.assertFalse(ids.contains(depositId)) {
      "expected kind=withdrawal to exclude the deposit ($depositId)"
    }
    Assertions.assertFalse(ids.contains(depositExchangeId)) {
      "expected kind=withdrawal to exclude the deposit-exchange ($depositExchangeId)"
    }
  }

  @Test
  fun `test sep6 GET transactions rejects unsupported asset_code`() {
    val ex =
      assertThrows<SepValidationException> {
        sep6Client.getTransactions(mapOf("asset_code" to "NOPE"))
      }
    assert(ex.message!!.contains("asset code NOPE not supported")) {
      "Expected an unsupported-asset error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 GET transactions rejects request without asset_code`() {
    val ex = assertThrows<SepException> { sep6Client.getTransactions(mapOf()) }
    assert(ex.message!!.contains("asset_code")) {
      "Expected a missing 'asset_code' parameter error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 GET transactions rejects request without JWT`() {
    val noAuthClient = Sep6Client(toml.getString("TRANSFER_SERVER"), null)
    assertThrows<SepNotAuthorizedException> {
      noAuthClient.getTransactions(mapOf("asset_code" to "USDC"))
    }
  }

  @Test
  fun `test sep6 GET transaction rejects request without JWT`() {
    val noAuthClient = Sep6Client(toml.getString("TRANSFER_SERVER"), null)
    assertThrows<SepNotAuthorizedException> { noAuthClient.getTransaction(mapOf("id" to "any-id")) }
  }

  @Test
  fun `test sep6 GET transaction rejects request naming no transaction`() {
    val ex = assertThrows<SepValidationException> { sep6Client.getTransaction(mapOf()) }
    assert(
      ex.message!!.contains(
        "One of id, stellar_transaction_id, or external_transaction_id is required"
      )
    ) {
      "Expected a missing-identifier error but got: ${ex.message}"
    }
  }

  @Test
  fun `test sep6 GET transaction returns 404 for an unknown id`() {
    val ex =
      assertThrows<SepNotFoundException> {
        sep6Client.getTransaction(mapOf("id" to UUID.randomUUID().toString()))
      }
    Assertions.assertEquals("transaction not found", ex.message)
  }

  @Test
  fun `test sep6 GET transaction returns 404 for an unknown external_transaction_id`() {
    val ex =
      assertThrows<SepNotFoundException> {
        sep6Client.getTransaction(
          mapOf("external_transaction_id" to "unknown-${UUID.randomUUID()}")
        )
      }
    Assertions.assertEquals("transaction not found", ex.message)
  }

  @Test
  fun `test sep6 GET transaction returns 404 for an unknown stellar_transaction_id`() {
    val ex =
      assertThrows<SepNotFoundException> {
        sep6Client.getTransaction(mapOf("stellar_transaction_id" to "unknown-${UUID.randomUUID()}"))
      }
    Assertions.assertEquals("transaction not found", ex.message)
  }

  @Test
  fun `test sep6 GET transaction hides a transaction belonging to a different account`() {
    val ownerKeyPair = SigningKeyPair(KeyPair.random())
    val ownerJwt = authenticateWithoutMemo(ownerKeyPair)
    val ownerClient = Sep6Client(toml.getString("TRANSFER_SERVER"), ownerJwt)
    val depositId =
      ownerClient.deposit(mapOf("asset_code" to "USDC", "amount" to "1", "type" to "SWIFT")).id!!

    val strangerKeyPair = SigningKeyPair(KeyPair.random())
    val strangerJwt = authenticateWithoutMemo(strangerKeyPair)
    val strangerClient = Sep6Client(toml.getString("TRANSFER_SERVER"), strangerJwt)

    val ex =
      assertThrows<SepNotFoundException> { strangerClient.getTransaction(mapOf("id" to depositId)) }
    Assertions.assertEquals("transaction not found", ex.message)
  }

  @Test
  fun `test sep6 GET transaction hides a transaction belonging to a different memo on the same account`() {
    val sharedKeyPair = SigningKeyPair(KeyPair.random())
    val memoAJwt = authenticateWithMemo(sharedKeyPair, 111UL)
    val memoAClient = Sep6Client(toml.getString("TRANSFER_SERVER"), memoAJwt)
    val depositId =
      memoAClient.deposit(mapOf("asset_code" to "USDC", "amount" to "1", "type" to "SWIFT")).id!!

    val memoBJwt = authenticateWithMemo(sharedKeyPair, 222UL)
    val memoBClient = Sep6Client(toml.getString("TRANSFER_SERVER"), memoBJwt)

    val ex =
      assertThrows<SepNotFoundException> { memoBClient.getTransaction(mapOf("id" to depositId)) }
    Assertions.assertEquals("transaction not found", ex.message)
  }

  @Test
  fun `test sep6 GET transaction resolves a transaction by external_transaction_id`() {
    val keyPair = SigningKeyPair(KeyPair.random())
    val jwt = authenticateWithoutMemo(keyPair)
    val client = Sep6Client(toml.getString("TRANSFER_SERVER"), jwt)
    val depositId =
      client.deposit(mapOf("asset_code" to "USDC", "amount" to "1", "type" to "SWIFT")).id!!

    val externalTransactionId = "sep6-404s-external-${UUID.randomUUID()}"
    val rpcActionRequestsJson =
      SEP6_EXTERNAL_TRANSACTION_ID_FLOW_ACTION_REQUESTS.replace("%TX_ID%", depositId)
        .replace("%EXTERNAL_TRANSACTION_ID%", externalTransactionId)
    val rpcActionRequestsType = object : TypeToken<List<RpcRequest>>() {}.type
    val rpcActionRequests: List<RpcRequest> =
      gson.fromJson(rpcActionRequestsJson, rpcActionRequestsType)
platformApiClient.sendRpcRequest(rpcActionRequests).use { response ->
  Assertions.assertTrue(response.isSuccessful) {
    "RPC setup failed with HTTP ${response.code}"
  }
}

    val found = client.getTransaction(mapOf("external_transaction_id" to externalTransactionId))
    Assertions.assertEquals(depositId, found.transaction.id)
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
  fun `test sep6 deposit falls back to the JWT's own account when account param is omitted`() {
    val request = mapOf("asset_code" to "USDC", "amount" to "1", "type" to "SWIFT")
    val response = sep6Client.deposit(request)
    Log.info("GET /deposit response: $response")
    assert(!response.id.isNullOrEmpty())

    val savedDepositTxn = sep6Client.getTransaction(mapOf("id" to response.id!!))
    Assertions.assertEquals(clientWalletAccount, savedDepositTxn.transaction.to)
  }

  @Test
  fun `test sep6 deposit-exchange falls back to the JWT's own account when account param is omitted`() {
    val request =
      mapOf(
        "destination_asset" to "USDC",
        "source_asset" to "iso4217:USD",
        "amount" to "1",
        "type" to "SWIFT",
      )
    val response = sep6Client.deposit(request, exchange = true)
    Log.info("GET /deposit-exchange response: $response")
    assert(!response.id.isNullOrEmpty())

    val savedDepositTxn = sep6Client.getTransaction(mapOf("id" to response.id!!))
    Assertions.assertEquals(clientWalletAccount, savedDepositTxn.transaction.to)
  }

  @Test
  fun `test sep6 GET transactions returns own transactions when account param is omitted`() {
    val depositId =
      sep6Client
        .deposit(
          mapOf(
            "asset_code" to "USDC",
            "account" to clientWalletAccount,
            "amount" to "1",
            "type" to "SWIFT",
          )
        )
        .id!!

    val response = sep6Client.getTransactions(mapOf("asset_code" to "USDC"))

    Assertions.assertTrue(response.transactions.map { it.id }.contains(depositId)) {
      "expected the JWT's own deposit ($depositId) to be present when account is omitted"
    }
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

    private val SEP6_EXTERNAL_TRANSACTION_ID_FLOW_ACTION_REQUESTS =
      """
      [
        {
          "id": "1",
          "method": "request_offchain_funds",
          "jsonrpc": "2.0",
          "params": {
            "transaction_id": "%TX_ID%",
            "message": "test message 1",
            "amount_in": { "amount": "1", "asset": "iso4217:USD" },
            "amount_out": {
              "amount": "1",
              "asset": "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
            },
            "fee_details": { "total": "0", "asset": "iso4217:USD" },
            "amount_expected": { "amount": "1" }
          }
        },
        {
          "id": "2",
          "method": "notify_offchain_funds_received",
          "jsonrpc": "2.0",
          "params": {
            "transaction_id": "%TX_ID%",
            "message": "test message 2",
            "external_transaction_id": "%EXTERNAL_TRANSACTION_ID%",
            "amount_in": { "amount": "1" }
          }
        }
      ]
      """
        .trimIndent()

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
