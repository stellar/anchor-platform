package org.stellar.reference.wallet

import com.google.gson.JsonObject
import java.time.Duration
import java.time.Instant
import java.util.*
import org.stellar.sdk.KeyPair

class CallbackService {
  private val callbacks = mutableMapOf<CallbackType, MutableList<JsonObject>>()

  init {
    CallbackType.entries.forEach { callbacks[it] = mutableListOf() }
  }

  fun processCallback(receivedCallback: JsonObject, type: String) {
    val callbackType = CallbackType.fromString(type)
    callbacks[callbackType]?.add(receivedCallback)
  }

  fun getTransactionCallbacks(type: String, txnId: String?): List<JsonObject> {
    val callbackType = CallbackType.fromString(type)
    if (callbackType == CallbackType.SEP12) {
      throw IllegalArgumentException("SEP12 is not a valid transaction type")
    }
    return callbacks[callbackType]?.let { callbacks ->
      txnId?.let { id ->
        callbacks.filter { it.getAsJsonObject("transaction")?.get("id")?.asString == id }
      }
        ?: callbacks
    }
      ?: emptyList()
  }

  fun getCustomerCallbacks(customerId: String?): List<JsonObject> {
    return callbacks[CallbackType.SEP12]?.let { callbacks ->
      customerId?.let { id -> callbacks.filter { it.get("id").asString == id } } ?: callbacks
    }
      ?: emptyList()
  }

  companion object {
    fun verifySignature(
      header: String?,
      body: String?,
      domain: String?,
      signer: KeyPair?
    ): Boolean {
      return SignatureVerifier(header, body, domain, signer).verify()
    }
  }

  private enum class CallbackType {
    SEP6,
    SEP24,
    SEP31,
    SEP12;

    companion object {
      fun fromString(type: String): CallbackType {
        return when (type.lowercase()) {
          "sep6" -> SEP6
          "sep24" -> SEP24
          "sep31" -> SEP31
          "sep12" -> SEP12
          else -> throw IllegalArgumentException("Invalid type: $type")
        }
      }
    }
  }
}

private class SignatureVerifier(
  private val header: String?,
  private val body: String?,
  private val domain: String?,
  private val signer: KeyPair?
) {

  fun verify(): Boolean {
    if (!validateInputs()) return false

    val (timestamp, signature) = parseHeader() ?: return false
    if (!validateTimestamp(timestamp)) return false

    return verifySignature(timestamp, signature)
  }

  private fun validateInputs(): Boolean {
    if (header == null) {
      log.warn("Failed to verify signature: Signature header is null")
      return false
    }
    if (body == null) {
      log.warn("Failed to verify signature: Body is null")
      return false
    }
    if (signer == null) {
      log.warn("Failed to verify signature: Signer is null")
      return false
    }
    return true
  }

  private fun parseHeader(): Pair<Long, ByteArray>? {
    val tokens = header!!.split(",")
    if (tokens.size != 2) {
      log.warn("Failed to verify signature: Invalid signature header")
      return null
    }

    val timestamp = parseTimestamp(tokens[0]) ?: return null
    val signature = parseSignature(tokens[1]) ?: return null

    return Pair(timestamp, signature)
  }

  private fun parseTimestamp(token: String): Long? {
    val timestampTokens = token.trim().split("=")
    if (timestampTokens.size != 2 || timestampTokens[0] != "t") {
      log.warn("Failed to verify signature: Invalid timestamp in signature header")
      return null
    }
    return timestampTokens[1].trim().toLongOrNull()
  }

  private fun parseSignature(token: String): ByteArray? {
    val sigTokens = token.trim().split("=", limit = 2)
    if (sigTokens.size != 2 || sigTokens[0] != "s") {
      log.warn("Failed to verify signature: Invalid signature in signature header")
      return null
    }

    val sigBase64 = sigTokens[1].trim()
    if (sigBase64.isEmpty()) {
      log.warn("Failed to verify signature: Signature is empty")
      return null
    }

    return Base64.getDecoder().decode(sigBase64)
  }

  private fun validateTimestamp(timestamp: Long): Boolean {
    val instantTimestamp = Instant.ofEpochSecond(timestamp)
    if (Duration.between(instantTimestamp, Instant.now()).toMinutes() > 2) {
      log.warn("Failed to verify signature: Timestamp is older than 2 minutes")
      return false
    }
    return true
  }

  private fun verifySignature(timestamp: Long, signature: ByteArray): Boolean {
    val payloadToVerify = "$timestamp.$domain.$body"
    if (!signer!!.verify(payloadToVerify.toByteArray(), signature)) {
      log.warn("Failed to verify signature: Signature verification failed")
      return false
    }
    return true
  }
}
