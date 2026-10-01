package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.platform.integrationtest.Sep24SchemaCase.*

/**
 * Proves the hand-written SEP-24 transaction schema check accepts a compliant object and rejects
 * each way an object can break the schema `stellar-anchor-tests` applies. The e2e tests in
 * [Sep24Tests] lean on this check, so it must not be one that accepts anything.
 *
 * Needs no running stack.
 */
class Sep24TransactionSchemaTests {
  private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

  private val common =
    """
    "id": "d0a6a1c4-1d2e-4f77-9a6e-1a6f6f5f4c11",
    "status": "pending_user_transfer_start",
    "more_info_url": "http://localhost:8091/sep24/transaction/more_info?token=abc",
    "started_at": "2026-10-01T12:00:00.123456Z",
    "amount_in": "100",
    "amount_in_asset": "iso4217:USD",
    "amount_out": "95",
    "amount_out_asset": "stellar:USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP",
    """

  private fun pendingDeposit() =
    json(
      """{ $common "kind": "deposit", "to": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG" }"""
    )

  private fun pendingWithdrawal() =
    json(
      """{ $common "kind": "withdrawal",
           "from": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
           "withdraw_anchor_account": "GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5",
           "withdraw_memo": "123456",
           "withdraw_memo_type": "id" }"""
    )

  private fun incompleteDeposit() =
    json(
      """{ "id": "x", "kind": "deposit", "status": "incomplete",
           "more_info_url": "https://example.com/info", "started_at": "2026-10-01T12:00:00Z",
           "to": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG" }"""
    )

  private fun incompleteWithdrawal() =
    json(
      """{ "id": "x", "kind": "withdrawal", "status": "incomplete",
           "more_info_url": "https://example.com/info", "started_at": "2026-10-01T12:00:00Z",
           "from": "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG" }"""
    )

  /** The check must fail, and its message must name the offending [field]. */
  private fun assertRejected(txn: JsonObject, schemaCase: Sep24SchemaCase, field: String) {
    val ex = assertThrows<AssertionError> { assertSep24TransactionSchema(txn, schemaCase) }
    assertTrue(ex.message!!.contains("'$field'")) {
      "the failure must name '$field', but was: ${ex.message}"
    }
  }

  private fun JsonObject.without(field: String) = deepCopy().also { it.remove(field) }

  private fun JsonObject.with(field: String, value: String) =
    deepCopy().also { it.add(field, JsonParser.parseString(value)) }

  // S24CV-17: a compliant object passes

  @Test
  fun `accepts a compliant pending deposit`() {
    assertSep24TransactionSchema(pendingDeposit(), DEPOSIT_PENDING)
  }

  @Test
  fun `accepts a compliant pending_user_transfer_start withdrawal`() {
    assertSep24TransactionSchema(pendingWithdrawal(), WITHDRAWAL_PENDING_USER_TRANSFER_START)
  }

  @Test
  fun `accepts incomplete transactions that carry only the required fields`() {
    assertSep24TransactionSchema(incompleteDeposit(), DEPOSIT_INCOMPLETE)
    assertSep24TransactionSchema(incompleteWithdrawal(), WITHDRAWAL_INCOMPLETE)
  }

  @Test
  fun `accepts a required field that is null when its type allows null`() {
    val txn = pendingWithdrawal().with("withdraw_memo", "null").with("withdraw_memo_type", "null")

    assertSep24TransactionSchema(txn, WITHDRAWAL_PENDING_USER_TRANSFER_START)
  }

  @Test
  fun `accepts fields the schema does not list`() {
    val txn = pendingDeposit().with("quote_id", "\"q-1\"").with("fee_details", "{\"total\":\"5\"}")

    assertSep24TransactionSchema(txn, DEPOSIT_PENDING)
  }

  // S24CV-15: a missing required field is rejected, and named

  @Test
  fun `rejects a pending deposit without amount_in`() {
    assertRejected(pendingDeposit().without("amount_in"), DEPOSIT_PENDING, "amount_in")
  }

  @Test
  fun `rejects a pending withdrawal without withdraw_anchor_account`() {
    assertRejected(
      pendingWithdrawal().without("withdraw_anchor_account"),
      WITHDRAWAL_PENDING_USER_TRANSFER_START,
      "withdraw_anchor_account",
    )
  }

  @Test
  fun `rejects a deposit without to`() {
    assertRejected(incompleteDeposit().without("to"), DEPOSIT_INCOMPLETE, "to")
  }

  @Test
  fun `rejects a transaction without id`() {
    assertRejected(pendingDeposit().without("id"), DEPOSIT_PENDING, "id")
  }

  @Test
  fun `rejects a completed deposit without stellar_transaction_id`() {
    assertRejected(
      pendingDeposit().with("completed_at", "\"2026-10-01T12:05:00Z\""),
      DEPOSIT_COMPLETED,
      "stellar_transaction_id",
    )
  }

  // S24CV-16: a wrongly typed field is rejected, and named

  @Test
  fun `rejects amount_in sent as a number`() {
    assertRejected(pendingDeposit().with("amount_in", "100"), DEPOSIT_PENDING, "amount_in")
  }

  @Test
  fun `rejects a started_at that is not a date-time`() {
    assertRejected(
      pendingDeposit().with("started_at", "\"yesterday\""),
      DEPOSIT_PENDING,
      "started_at",
    )
  }

  @Test
  fun `rejects a status outside the SEP-24 enum`() {
    assertRejected(pendingDeposit().with("status", "\"on_hold\""), DEPOSIT_PENDING, "status")
  }

  @Test
  fun `rejects a more_info_url that is not absolute`() {
    assertRejected(
      pendingDeposit().with("more_info_url", "\"/sep24/transaction/more_info\""),
      DEPOSIT_PENDING,
      "more_info_url",
    )
  }

  @Test
  fun `rejects refunded sent as a string`() {
    assertRejected(pendingDeposit().with("refunded", "\"false\""), DEPOSIT_PENDING, "refunded")
  }

  @Test
  fun `rejects refunds sent as an array`() {
    assertRejected(pendingDeposit().with("refunds", "[]"), DEPOSIT_PENDING, "refunds")
  }

  @Test
  fun `rejects status_eta sent as a string`() {
    assertRejected(pendingDeposit().with("status_eta", "\"soon\""), DEPOSIT_PENDING, "status_eta")
  }

  @Test
  fun `rejects kyc_verified sent as a string`() {
    assertRejected(
      pendingDeposit().with("kyc_verified", "\"yes\""),
      DEPOSIT_PENDING,
      "kyc_verified",
    )
  }

  @Test
  fun `rejects a completed_at that is not a date-time`() {
    assertRejected(
      pendingDeposit().with("completed_at", "\"later\""),
      DEPOSIT_PENDING,
      "completed_at",
    )
  }

  @Test
  fun `rejects a null to on a deposit because to is not nullable`() {
    assertRejected(pendingDeposit().with("to", "null"), DEPOSIT_PENDING, "to")
  }

  @Test
  fun `rejects a withdrawal checked as a deposit`() {
    assertRejected(pendingWithdrawal(), DEPOSIT_PENDING, "kind")
  }

  @Test
  fun `rejects a deposit checked as a withdrawal`() {
    assertRejected(pendingDeposit(), WITHDRAWAL_PENDING_USER_TRANSFER_START, "kind")
  }

  // Every required field of every case, written out from the spec's schema table. The lists are
  // deliberately not read from the implementation: a check that dropped a field from its own list
  // would otherwise agree with a test that read the same list.

  private val alwaysRequired = listOf("id", "kind", "status", "more_info_url", "started_at")
  private val pendingAmounts =
    listOf("amount_in", "amount_in_asset", "amount_out", "amount_out_asset")
  private val completedFields = listOf("stellar_transaction_id", "completed_at")
  private val withdrawalPendingFields =
    pendingAmounts +
      listOf("withdraw_memo", "withdraw_memo_type", "withdraw_anchor_account", "from")

  private fun completed(txn: JsonObject) =
    txn
      .with("status", "\"completed\"")
      .with("stellar_transaction_id", "\"abc123\"")
      .with("completed_at", "\"2026-10-01T12:05:00Z\"")

  private fun compliantFixture(schemaCase: Sep24SchemaCase): JsonObject =
    when (schemaCase) {
      DEPOSIT_INCOMPLETE -> incompleteDeposit()
      DEPOSIT_PENDING -> pendingDeposit()
      DEPOSIT_COMPLETED -> completed(pendingDeposit())
      WITHDRAWAL_INCOMPLETE -> incompleteWithdrawal()
      WITHDRAWAL_PENDING_USER_TRANSFER_START -> pendingWithdrawal()
      WITHDRAWAL_COMPLETED -> completed(pendingWithdrawal())
    }

  private val requiredByCase: Map<Sep24SchemaCase, List<String>> =
    mapOf(
      DEPOSIT_INCOMPLETE to alwaysRequired + "to",
      DEPOSIT_PENDING to alwaysRequired + pendingAmounts + "to",
      DEPOSIT_COMPLETED to alwaysRequired + pendingAmounts + "to" + completedFields,
      WITHDRAWAL_INCOMPLETE to alwaysRequired + "from",
      WITHDRAWAL_PENDING_USER_TRANSFER_START to alwaysRequired + withdrawalPendingFields,
      WITHDRAWAL_COMPLETED to alwaysRequired + withdrawalPendingFields + completedFields,
    )

  @Test
  fun `accepts a compliant fixture for every case, so the removal tests below are not vacuous`() {
    Sep24SchemaCase.values().forEach { schemaCase ->
      assertSep24TransactionSchema(compliantFixture(schemaCase), schemaCase)
    }
  }

  @Test
  fun `rejects the removal of each required field of every case`() {
    Sep24SchemaCase.values().forEach { schemaCase ->
      requiredByCase.getValue(schemaCase).forEach { field ->
        assertRejected(compliantFixture(schemaCase).without(field), schemaCase, field)
      }
    }
  }

  @Test
  fun `rejects a null from on a withdrawal because from is not nullable there`() {
    assertRejected(
      pendingWithdrawal().with("from", "null"),
      WITHDRAWAL_PENDING_USER_TRANSFER_START,
      "from",
    )
  }

  @Test
  fun `accepts a null from on a deposit and a null to on a withdrawal`() {
    assertSep24TransactionSchema(pendingDeposit().with("from", "null"), DEPOSIT_PENDING)
    assertSep24TransactionSchema(
      pendingWithdrawal().with("to", "null"),
      WITHDRAWAL_PENDING_USER_TRANSFER_START,
    )
  }

  @Test
  fun `rejects an id sent as a number`() {
    assertRejected(pendingDeposit().with("id", "42"), DEPOSIT_PENDING, "id")
  }

  @Test
  fun `rejects a null more_info_url`() {
    assertRejected(pendingDeposit().with("more_info_url", "null"), DEPOSIT_PENDING, "more_info_url")
  }

  @Test
  fun `accepts each of the 15 SEP-24 statuses`() {
    listOf(
        "incomplete",
        "pending_anchor",
        "pending_external",
        "pending_stellar",
        "pending_trust",
        "pending_user",
        "pending_user_transfer_start",
        "pending_user_transfer_complete",
        "completed",
        "refunded",
        "expired",
        "no_market",
        "too_small",
        "too_large",
        "error",
      )
      .forEach { status ->
        assertSep24TransactionSchema(
          pendingDeposit().with("status", "\"$status\""),
          DEPOSIT_PENDING,
        )
      }
  }

  // S24CV-18: the more_info_url query carries a JWT, so no failure message may print it.

  private fun withTokenInUrl(txn: JsonObject) =
    txn.with("more_info_url", "\"https://example.com/info?token=SECRET\"")

  private fun assertMessageHasNoSecret(
    field: String,
    block: () -> Unit,
  ) {
    val ex = assertThrows<AssertionError> { block() }
    assertTrue(ex.message!!.contains("'$field'")) {
      "the failure must still name '$field', but was: ${ex.message}"
    }
    assertTrue(!ex.message!!.contains("SECRET")) {
      "the failure leaked the more_info_url query: ${ex.message}"
    }
  }

  @Test
  fun `a missing-field failure does not print the more_info_url query`() {
    val txn = withTokenInUrl(pendingDeposit()).without("amount_in")

    assertMessageHasNoSecret("amount_in") { assertSep24TransactionSchema(txn, DEPOSIT_PENDING) }
  }

  @Test
  fun `a missing-kind failure does not print the more_info_url query`() {
    val txn = withTokenInUrl(pendingDeposit()).without("kind")

    assertMessageHasNoSecret("kind") { assertSep24TransactionSchema(txn, DEPOSIT_PENDING) }
  }

  @Test
  fun `a more_info_url of the wrong type is reported without its query`() {
    val txn = pendingDeposit().with("more_info_url", "\"/sep24/more_info?token=SECRET\"")

    assertMessageHasNoSecret("more_info_url") { assertSep24TransactionSchema(txn, DEPOSIT_PENDING) }
  }

  @Test
  fun `reading a missing string field does not print the more_info_url query`() {
    val txn = withTokenInUrl(pendingDeposit())

    assertMessageHasNoSecret("no_such_field") { requireJsonString(txn, "no_such_field") }
  }

  @Test
  fun `redaction keeps the URL up to the query and leaves a URL without a query alone`() {
    assertTrue(
      redactedForLog(withTokenInUrl(pendingDeposit())).contains("\"https://example.com/info\"")
    )
    assertTrue(
      redactedForLog(pendingDeposit().with("more_info_url", "\"https://example.com/plain\""))
        .contains("\"https://example.com/plain\"")
    )
  }
}
