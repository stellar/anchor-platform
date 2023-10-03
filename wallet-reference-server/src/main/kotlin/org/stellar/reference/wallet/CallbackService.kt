package org.stellar.reference.wallet

import java.time.Duration
import java.time.Instant
import java.util.*
import org.stellar.sdk.KeyPair

class CallbackService {

  /** Used to extract common fields from a SEP-6/24 callback * */
  private data class CallbackEvent(val transaction: Transaction)

  private data class Transaction(val id: String)

  private val receivedCallbacks: MutableList<String> = mutableListOf()

  fun processCallback(receivedCallback: String) {
    receivedCallbacks.add(receivedCallback)
  }

  // Get all events. This is for testing purpose.
  // If txnId is not null, the events are filtered.
  fun getCallbacks(txnId: String?): List<String> {
    if (txnId != null) {
      // filter events with txnId
      return receivedCallbacks.filter {
        gson.fromJson(it, CallbackEvent::class.java).transaction.id == txnId
      }
    }
    // return all events
    return receivedCallbacks
  }

  // Get the latest event received. This is for testing purpose
  fun getLatestCallback(): String? {
    return receivedCallbacks.lastOrNull()
  }

  // Clear all events. This is for testing purpose
  fun clear() {
    log.debug("Clearing events")
    receivedCallbacks.clear()
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
