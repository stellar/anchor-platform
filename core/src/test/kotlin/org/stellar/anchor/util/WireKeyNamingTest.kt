package org.stellar.anchor.util

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.asset.AssetInfo
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.sep.sep10.ChallengeRequest as Sep10ChallengeRequest
import org.stellar.anchor.api.sep.sep45.ChallengeRequest as Sep45ChallengeRequest
import org.stellar.anchor.api.sep.sep45.ChallengeResponse
import org.stellar.anchor.api.sep.sep45.ValidationRequest

/**
 * Pins the JSON keys the shipped DTOs use on the wire. The servers hand every body to
 * `GsonUtils.getInstance()`, which ignores Jackson annotations and uses the raw Java name for a
 * field without `@SerializedName`, so these tests read the raw keys instead of round-tripping
 * through the same DTO (a wrong key is symmetric there and goes unnoticed).
 */
class WireKeyNamingTest {
  private val gson = GsonUtils.getInstance()

  private fun toJson(obj: Any): JsonObject = gson.toJsonTree(obj).asJsonObject

  // WIRE-01, WIRE-03
  @Test
  fun `test fundingMethod is serialized as funding_method`() {
    val json = toJson(GetTransactionResponse.builder().fundingMethod("SEPA").build())

    assertEquals("SEPA", json.get("funding_method").asString)
    assertFalse(json.has("fundingMethod"), "keys: ${json.keySet()}")
  }

  // WIRE-02, WIRE-03
  @Test
  fun `test requiredInfoUpdatesFields is serialized as required_info_updates_fields`() {
    val json =
      toJson(
        GetTransactionResponse.builder()
          .requiredInfoUpdatesFields(
            mapOf("bank_name" to AssetInfo.Field("The bank's name", listOf("a", "b"), true))
          )
          .build()
      )

    val field = json.getAsJsonObject("required_info_updates_fields").getAsJsonObject("bank_name")
    assertEquals("The bank's name", field.get("description").asString)
    assertEquals(listOf("a", "b"), field.getAsJsonArray("choices").map { it.asString })
    assertTrue(field.get("optional").asBoolean)
    assertFalse(json.has("requiredInfoUpdatesFields"), "keys: ${json.keySet()}")
  }

  // WIRE-06
  @Test
  fun `test GetTransactionResponse is populated from funding_method and required_info_updates_fields`() {
    val response =
      gson.fromJson(
        """
        {
          "funding_method": "SWIFT",
          "required_info_updates_fields": {"bank_name": {"description": "d", "optional": false}}
        }
        """,
        GetTransactionResponse::class.java,
      )

    assertEquals("SWIFT", response.fundingMethod)
    assertEquals("d", response.requiredInfoUpdatesFields["bank_name"]!!.description)
  }

  // WIRE-07
  @Test
  fun `test a fully populated GetTransactionResponse serializes exactly the documented keys`() {
    val full =
      """
      {
        "id": "tx1", "sep": "31", "kind": "receive", "status": "completed", "type": "bank_account",
        "amount_expected": {"amount": "1", "asset": "USD"},
        "amount_in": {"amount": "1", "asset": "USD"},
        "amount_out": {"amount": "1", "asset": "USD"},
        "fee_details": {"total": "1", "asset": "USD"},
        "quote_id": "q", "started_at": "2024-01-01T00:00:00Z", "updated_at": "2024-01-01T00:00:00Z",
        "completed_at": "2024-01-01T00:00:00Z", "user_action_required_by": "2024-01-01T00:00:00Z",
        "transfer_received_at": "2024-01-01T00:00:00Z", "message": "m", "refunds": {},
        "stellar_transactions": [], "source_account": "a", "destination_account": "b",
        "external_transaction_id": "e", "memo": "m", "memo_type": "text", "refund_memo": "m",
        "refund_memo_type": "text", "withdraw_anchor_account": "w", "client_domain": "d",
        "client_name": "n", "customers": {"sender": {"id": "s"}}, "creator": {"id": "c"},
        "required_info_message": "r", "required_info_updates": ["f"],
        "required_info_updates_fields": {"f": {"description": "d"}},
        "request_client_ip_address": "1.2.3.4", "instructions": {"i": {"value": "v"}},
        "funding_method": "SEPA", "fields": {"f": "v"}
      }
      """

    val json = toJson(gson.fromJson(full, GetTransactionResponse::class.java))

    assertEquals(
      gson.fromJson(full, JsonObject::class.java).keySet().sorted(),
      json.keySet().sorted(),
    )
  }

  // edge cases: null keys are omitted
  @Test
  fun `test null fundingMethod and requiredInfoUpdatesFields are omitted`() {
    val json = toJson(GetTransactionResponse.builder().id("tx1").build())

    assertFalse(json.has("funding_method"), "keys: ${json.keySet()}")
    assertFalse(json.has("required_info_updates_fields"), "keys: ${json.keySet()}")
  }

  // WIRE-08
  @Test
  fun `test SEP-45 ChallengeResponse is serialized with the spec keys`() {
    val json =
      toJson(
        ChallengeResponse.builder()
          .authorizationEntries("entries")
          .networkPassphrase("passphrase")
          .build()
      )

    assertEquals(setOf("authorization_entries", "network_passphrase"), json.keySet())
    assertEquals("entries", json.get("authorization_entries").asString)
    assertEquals("passphrase", json.get("network_passphrase").asString)
  }

  // WIRE-08
  @Test
  fun `test SEP-45 ValidationRequest is populated from authorization_entries`() {
    val request =
      gson.fromJson("""{"authorization_entries": "entries"}""", ValidationRequest::class.java)

    assertEquals("entries", request.authorizationEntries)
  }

  // WIRE-11
  @Test
  fun `test SEP-45 and SEP-10 ChallengeRequest read home_domain and client_domain`() {
    val body =
      """{"account": "G1", "home_domain": "home.example", "client_domain": "client.example"}"""

    val sep45 = gson.fromJson(body, Sep45ChallengeRequest::class.java)
    val sep10 = gson.fromJson(body, Sep10ChallengeRequest::class.java)

    assertEquals("home.example", sep45.homeDomain)
    assertEquals("client.example", sep45.clientDomain)
    assertEquals("home.example", sep10.homeDomain)
    assertEquals("client.example", sep10.clientDomain)
  }

  // WIRE-11
  @Test
  fun `test SEP-45 and SEP-10 ChallengeRequest write home_domain and client_domain`() {
    val sep45 =
      toJson(
        Sep45ChallengeRequest.builder()
          .account("G1")
          .homeDomain("home.example")
          .clientDomain("client.example")
          .build()
      )
    val sep10 =
      toJson(
        Sep10ChallengeRequest.builder()
          .account("G1")
          .homeDomain("home.example")
          .clientDomain("client.example")
          .build()
      )

    assertEquals(setOf("account", "home_domain", "client_domain"), sep45.keySet())
    assertEquals(setOf("account", "home_domain", "client_domain"), sep10.keySet())
  }
}
