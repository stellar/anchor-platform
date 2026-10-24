package org.stellar.anchor.platform

import java.util.Base64
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.stellar.reference.wallet.RawCallback
import org.stellar.sdk.KeyPair

/**
 * Verifies each callback the way a wallet server must: with the public `SIGNING_KEY` from the TOML
 * ([signingKey]), over `<t>.<host>.<body>`, where `host` is the authority the callback was sent to
 * ([expectedHost]) and `body` is the exact bytes received. Nothing here trusts the wallet reference
 * server's own check.
 */
fun assertCallbackSignatures(
  rawCallbacks: List<RawCallback>,
  signingKey: String,
  expectedHost: String?,
  expectedCount: Int,
  notBefore: Long,
) {
  assertEquals(expectedCount, rawCallbacks.size)
  val verifier = KeyPair.fromAccountId(signingKey)
  val notAfter = System.currentTimeMillis() / 1000

  rawCallbacks.forEach { raw ->
    assertEquals(expectedHost, raw.host) {
      "callback Host must be the wallet.hostname the test profile declares"
    }
    val (t, signature) = parseSignatureHeader(raw)
    assertTrue(verifier.verify("$t.${raw.host}.${raw.body}".toByteArray(), signature)) {
      "callback signature does not verify over $t.${raw.host}.<body>: ${raw.signature}"
    }
    assertTrue(t.toLong() in notBefore..notAfter) {
      "callback timestamp $t outside [$notBefore, $notAfter]"
    }
  }

  // The check is not vacuous: change one byte in the middle of any body and its own signature
  // no longer verifies.
  rawCallbacks.forEach { raw ->
    val (t, signature) = parseSignatureHeader(raw)
    assertFalse(
      verifier.verify("$t.${raw.host}.${withMiddleByteChanged(raw.body)}".toByteArray(), signature)
    ) {
      "a callback whose body was changed must not verify: ${raw.signature}"
    }
  }
}

private fun withMiddleByteChanged(body: String): String {
  val middle = body.length / 2
  val replacement = if (body[middle] == 'x') 'y' else 'x'
  return body.substring(0, middle) + replacement + body.substring(middle + 1)
}

private fun parseSignatureHeader(raw: RawCallback): Pair<String, ByteArray> {
  val match = Regex("^t=(\\d+), s=([A-Za-z0-9+/]+=*)$").matchEntire(raw.signature ?: "")
  assertNotNull(match) { "unexpected Signature header: ${raw.signature}" }
  val (t, s) = match!!.destructured
  return t to Base64.getDecoder().decode(s)
}
