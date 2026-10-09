package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.net.URI
import java.time.OffsetDateTime
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/** The kind and status a SEP-24 transaction is checked as. The case carries the kind. */
internal enum class Sep24SchemaCase(val kind: String) {
  DEPOSIT_INCOMPLETE("deposit"),
  DEPOSIT_PENDING("deposit"),
  DEPOSIT_COMPLETED("deposit"),
  WITHDRAWAL_INCOMPLETE("withdrawal"),
  WITHDRAWAL_PENDING_USER_TRANSFER_START("withdrawal"),
  WITHDRAWAL_COMPLETED("withdrawal"),
}

private enum class JsonType(val label: String) {
  STRING("a string"),
  NUMBER("a number"),
  BOOLEAN("a boolean"),
  OBJECT("an object"),
  DATE_TIME("an ISO-8601 date-time string"),
  URI_ABSOLUTE("an absolute URI string"),
}

private val SEP24_STATUSES =
  setOf(
    "incomplete",
    "pending_anchor",
    "pending_external",
    "pending_stellar",
    "pending_trust",
    "pending_user",
    "pending_user_transfer_start",
    "pending_user_transfer_complete",
    "on_hold",
    "completed",
    "refunded",
    "expired",
    "no_market",
    "too_small",
    "too_large",
    "error",
  )

/** Property -> (type, nullable), for the properties every SEP-24 transaction may carry. */
private val COMMON_PROPERTIES: Map<String, Pair<JsonType, Boolean>> =
  mapOf(
    "status_eta" to (JsonType.NUMBER to true),
    "kyc_verified" to (JsonType.BOOLEAN to true),
    "amount_in" to (JsonType.STRING to true),
    "amount_in_asset" to (JsonType.STRING to true),
    "amount_out" to (JsonType.STRING to true),
    "amount_out_asset" to (JsonType.STRING to true),
    "amount_fee" to (JsonType.STRING to true),
    "amount_fee_asset" to (JsonType.STRING to true),
    "completed_at" to (JsonType.DATE_TIME to true),
    "updated_at" to (JsonType.DATE_TIME to true),
    "stellar_transaction_id" to (JsonType.STRING to true),
    "external_transaction_id" to (JsonType.STRING to true),
    "message" to (JsonType.STRING to true),
    "refunded" to (JsonType.BOOLEAN to true),
    "refunds" to (JsonType.OBJECT to true),
  )

private val DEPOSIT_PROPERTIES: Map<String, Pair<JsonType, Boolean>> =
  mapOf(
    "deposit_memo" to (JsonType.STRING to true),
    "deposit_memo_type" to (JsonType.STRING to true),
    "from" to (JsonType.STRING to true),
    "to" to (JsonType.STRING to false),
    "claimable_balance_id" to (JsonType.STRING to true),
  )

private val WITHDRAWAL_PROPERTIES: Map<String, Pair<JsonType, Boolean>> =
  mapOf(
    "withdraw_anchor_account" to (JsonType.STRING to true),
    "withdraw_memo" to (JsonType.STRING to true),
    "withdraw_memo_type" to (JsonType.STRING to true),
    "from" to (JsonType.STRING to false),
    "to" to (JsonType.STRING to true),
  )

private val ALWAYS_REQUIRED = listOf("id", "kind", "status", "more_info_url", "started_at")

private val PENDING_AMOUNTS =
  listOf("amount_in", "amount_in_asset", "amount_out", "amount_out_asset")

private val COMPLETED_FIELDS = listOf("stellar_transaction_id", "completed_at")

private fun requiredFor(schemaCase: Sep24SchemaCase): List<String> =
  ALWAYS_REQUIRED +
    when (schemaCase) {
      Sep24SchemaCase.DEPOSIT_INCOMPLETE -> listOf("to")
      Sep24SchemaCase.DEPOSIT_PENDING -> PENDING_AMOUNTS + "to"
      Sep24SchemaCase.DEPOSIT_COMPLETED -> COMPLETED_FIELDS + PENDING_AMOUNTS + "to"
      Sep24SchemaCase.WITHDRAWAL_INCOMPLETE -> listOf("from")
      Sep24SchemaCase.WITHDRAWAL_PENDING_USER_TRANSFER_START ->
        PENDING_AMOUNTS +
          listOf("withdraw_memo", "withdraw_memo_type", "withdraw_anchor_account", "from")
      Sep24SchemaCase.WITHDRAWAL_COMPLETED ->
        COMPLETED_FIELDS +
          PENDING_AMOUNTS +
          listOf("withdraw_memo", "withdraw_memo_type", "withdraw_anchor_account", "from")
    }

/**
 * Checks the raw `transaction` object of a SEP-24 `GET /transaction` answer against the schema
 * `stellar-anchor-tests` applies (`schemas/sep24.ts`, `getTransactionSchema`): the fields it
 * requires for [schemaCase] are present, and every field it lists has an allowed type. Fields the
 * schema does not list are allowed. Works on the raw JSON: Gson silently nulls absent fields and
 * drops unknown ones, so a parsed object cannot back a schema check.
 *
 * Every failure names the offending field.
 */
internal fun assertSep24TransactionSchema(transaction: JsonObject, schemaCase: Sep24SchemaCase) {
  // The kind goes first: a deposit checked as a withdrawal would otherwise be reported as a long
  // list of missing withdrawal fields instead of as the wrong kind it is.
  if (!transaction.has("kind")) {
    fail<Unit>("'kind' is required but is missing: ${redactedForLog(transaction)}")
  }
  checkType(transaction, "kind", JsonType.STRING, nullable = false)
  val kind = transaction.get("kind").asString
  if (kind != schemaCase.kind) {
    fail<Unit>("'kind' must be '${schemaCase.kind}' for ${schemaCase.name}, got '$kind'")
  }

  requiredFor(schemaCase).forEach { field ->
    if (!transaction.has(field)) {
      fail<Unit>(
        "'$field' is required for a ${schemaCase.name} transaction but is missing: ${redactedForLog(transaction)}"
      )
    }
  }

  checkType(transaction, "id", JsonType.STRING, nullable = false)
  checkType(transaction, "status", JsonType.STRING, nullable = false)
  val status = transaction.get("status").asString
  if (status !in SEP24_STATUSES) {
    fail<Unit>("'status' must be one of the SEP-24 statuses, got '$status'")
  }
  checkType(transaction, "more_info_url", JsonType.URI_ABSOLUTE, nullable = false)
  checkType(transaction, "started_at", JsonType.DATE_TIME, nullable = false)

  val kindProperties =
    if (schemaCase.kind == "deposit") DEPOSIT_PROPERTIES else WITHDRAWAL_PROPERTIES
  (COMMON_PROPERTIES + kindProperties).forEach { (field, spec) ->
    if (transaction.has(field)) checkType(transaction, field, spec.first, spec.second)
  }
}

private fun checkType(obj: JsonObject, field: String, type: JsonType, nullable: Boolean) {
  val value: JsonElement = obj.get(field)
  if (value is JsonNull) {
    if (!nullable) fail<Unit>("'$field' must be ${type.label}, got null")
    return
  }
  val ok =
    when (type) {
      JsonType.STRING -> value.isJsonPrimitive && value.asJsonPrimitive.isString
      JsonType.NUMBER -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber
      JsonType.BOOLEAN -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
      JsonType.OBJECT -> value.isJsonObject
      JsonType.DATE_TIME ->
        value.isJsonPrimitive &&
          value.asJsonPrimitive.isString &&
          runCatching { OffsetDateTime.parse(value.asString) }.isSuccess
      JsonType.URI_ABSOLUTE ->
        value.isJsonPrimitive &&
          value.asJsonPrimitive.isString &&
          runCatching { URI(value.asString).isAbsolute }.getOrDefault(false)
    }
  if (!ok) {
    val allowed = if (nullable) "${type.label} or null" else type.label
    fail<Unit>("'$field' must be $allowed, got ${valueForLog(field, value)}")
  }
}

/**
 * The object as text for a failure message, with `more_info_url` cut at its `?`: the query carries
 * a JWT, and a failure message ends up in CI logs.
 */
internal fun redactedForLog(transaction: JsonObject): String {
  val copy = transaction.deepCopy()
  val url = copy.get("more_info_url")
  if (url != null && url.isJsonPrimitive && url.asJsonPrimitive.isString) {
    copy.addProperty("more_info_url", url.asString.substringBefore('?'))
  }
  return copy.toString()
}

/** A string field of [obj], failing with a message that names the field. */
internal fun requireJsonString(obj: JsonObject, field: String): String {
  val element = obj.get(field)
  assertTrue(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
    "expected '$field' to be a string in ${redactedForLog(obj)}"
  }
  return element.asString
}

/**
 * A field value for a failure message; `more_info_url` is cut at its `?` (see [redactedForLog]).
 */
private fun valueForLog(field: String, value: JsonElement): String =
  if (field == "more_info_url" && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
    "\"${value.asString.substringBefore('?')}\""
  } else {
    value.toString()
  }
