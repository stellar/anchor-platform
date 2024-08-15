package org.stellar.reference.wallet

import com.google.gson.JsonObject
import java.time.Duration
import java.time.Instant
import java.util.*
import org.stellar.sdk.KeyPair

class CallbackService {
  private val sep6Callbacks: MutableList<JsonObject> = mutableListOf()
  private val sep24Callbacks: MutableList<JsonObject> = mutableListOf()
  private val sep31Callbacks: MutableList<JsonObject> = mutableListOf()
  private val sep12Callbacks: MutableList<JsonObject> = mutableListOf()

  fun processCallback(receivedCallback: JsonObject, type: String) {
    when (type) {
      "sep6" -> sep6Callbacks.add(receivedCallback)
      "sep24" -> sep24Callbacks.add(receivedCallback)
      "sep31" -> sep31Callbacks.add(receivedCallback)
      "sep12" -> sep12Callbacks.add(receivedCallback)
      else -> throw IllegalArgumentException("Invalid type: $type")
    }
  }

  // Get all events. This is for testing purpose.
  // If txnId is not null, the events are filtered.
  fun getTransactionCallbacks(type: String, txnId: String?): List<JsonObject> {
    val receivedCallbacks =
      when (type) {
        "sep6" -> sep6Callbacks
        "sep24" -> sep24Callbacks
        "sep31" -> sep31Callbacks
        else -> throw IllegalArgumentException("Invalid type: $type")
      }
    if (txnId != null) {
      // filter events with txnId
      return receivedCallbacks.filter {
        it.getAsJsonObject("transaction")?.get("id")?.asString == txnId
      }
    }
    // return all events
    return receivedCallbacks
  }

  fun getCustomerCallbacks(customerId: String?): List<JsonObject> {
    if (customerId != null) {
      return sep12Callbacks.filter { it.get("id").asString == customerId }
    }
    return sep12Callbacks
  }

  companion object {
    fun verifySignature(
      header: String?,
      body: String?,
      domain: String?,
      signer: KeyPair?
    ): Boolean {
      val messagePrefix = "Failed to verify signature"
      if (header == null) {
        log.warn("$messagePrefix: Signature header is null")
        return false
      }
      val tokens = header.split(",")
      if (tokens.size != 2) {
        log.warn("$messagePrefix: Invalid signature header")
        return false
      }
      // t=timestamp
      val timestampTokens = tokens[0].trim().split("=")
      if (timestampTokens.size != 2 || timestampTokens[0] != "t") {
        log.warn("$messagePrefix: Invalid timestamp in signature header")
        return false
      }
      val timestampLong = timestampTokens[1].trim().toLongOrNull() ?: return false
      val timestamp = Instant.ofEpochSecond(timestampLong)

      if (Duration.between(timestamp, Instant.now()).toMinutes() > 2) {
        // timestamp is older than 2 minutes
        log.warn("$messagePrefix: Timestamp is older than 2 minutes")
        return false
      }

      // s=signature
      val sigTokens = tokens[1].trim().split("=", limit = 2)
      if (sigTokens.size != 2 || sigTokens[0] != "s") {
        log.warn("$messagePrefix: Invalid signature in signature header")
        return false
      }

      val sigBase64 = sigTokens[1].trim()
      if (sigBase64.isEmpty()) {
        log.warn("$messagePrefix: Signature is empty")
        return false
      }

      val signature = Base64.getDecoder().decode(sigBase64)

      if (body == null) {
        log.warn("$messagePrefix: Body is null")
        return false
      }

      val payloadToVerify = "${timestampLong}.${domain}.${body}"
      if (signer == null) {
        log.warn("$messagePrefix: Signer is null")
        return false
      }

      if (!signer.verify(payloadToVerify.toByteArray(), signature)) {
        log.warn("$messagePrefix: Signature verification failed")
        return false
      }

      return true
    }
  }
}
