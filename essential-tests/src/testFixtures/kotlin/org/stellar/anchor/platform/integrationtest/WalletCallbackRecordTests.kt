package org.stellar.anchor.platform.integrationtest

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.reference.wallet.CallbackService
import org.stellar.reference.wallet.RawCallback

/**
 * Stack-free tests of the wallet reference server's callback recording. An e2e test can only verify
 * a callback's signature over the bytes AP signed if the server keeps them as received.
 */
class WalletCallbackRecordTests {
  // Spacing and key order a re-serialization of the parsed object would not reproduce.
  private val body = "{ \"transaction\" :  {\"status\":\"completed\",  \"id\":\"txn-1\"} }\n"

  private fun record(service: CallbackService, raw: RawCallback) =
    service.processCallback(JsonParser.parseString(raw.body).asJsonObject, "sep24", raw)

  // SEP24IF-32
  @Test
  fun `a recorded callback keeps its signature, host and body exactly as received`() {
    val service = CallbackService()
    record(service, RawCallback("t=1700000000, s=c2ln", "wallet-server:8092", body))

    val recorded = service.getRawTransactionCallbacks("sep24", "txn-1")

    assertEquals(listOf(RawCallback("t=1700000000, s=c2ln", "wallet-server:8092", body)), recorded)
    assertEquals(body, recorded.single().body)
    assertTrue(
      body != JsonParser.parseString(body).toString(),
      "the fixture body must differ from a re-serialization of itself",
    )
  }

  // SEP24IF-32
  @Test
  fun `the raw reader returns only the callbacks of the requested transaction`() {
    val service = CallbackService()
    val other = body.replace("txn-1", "txn-2")
    record(service, RawCallback("t=1, s=YQ==", "wallet-server:8092", body))
    record(service, RawCallback("t=2, s=Yg==", "wallet-server:8092", other))

    assertEquals(listOf(body), service.getRawTransactionCallbacks("sep24", "txn-1").map { it.body })
    assertEquals(
      listOf(other),
      service.getRawTransactionCallbacks("sep24", "txn-2").map { it.body }
    )
    assertEquals(
      listOf(body, other),
      service.getRawTransactionCallbacks("sep24", null).map { it.body },
    )
    assertEquals(emptyList<RawCallback>(), service.getRawTransactionCallbacks("sep24", "txn-3"))
  }

  // SEP24IF-32: the listing existing callers use is unchanged by the recording.
  @Test
  fun `the parsed callback listing is unchanged by the recording`() {
    val service = CallbackService()
    record(service, RawCallback("t=1, s=YQ==", "wallet-server:8092", body))

    val listed = service.getTransactionCallbacks("sep24", "txn-1")

    assertEquals(listOf(JsonParser.parseString(body).asJsonObject), listed)
    assertEquals(emptyList<Any>(), service.getTransactionCallbacks("sep24", "txn-2"))
    // A callback processed without the raw record, as before, never shows up in the raw reader.
    service.processCallback(
      JsonParser.parseString(body.replace("txn-1", "txn-9")).asJsonObject,
      "sep24"
    )
    assertEquals(1, service.getTransactionCallbacks("sep24", "txn-9").size)
    assertEquals(emptyList<RawCallback>(), service.getRawTransactionCallbacks("sep24", "txn-9"))
  }
}
