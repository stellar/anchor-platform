package org.stellar.anchor.platform.integrationtest

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.IntegrationTestBase
import org.stellar.anchor.platform.TestConfig
import org.stellar.anchor.platform.TestSecrets.SEP10_SIGNING_SEED
import org.stellar.anchor.platform.event.ClientStatusCallbackHandler
import org.stellar.reference.wallet.CallbackService.Companion.verifySignature
import org.stellar.sdk.KeyPair
import org.stellar.sdk.KeyPair.fromSecretSeed
import org.stellar.sdk.KeyPair.random

class CallbackSignatureTest : IntegrationTestBase(TestConfig()) {
  @Test
  fun `test the SEP24 callback signature creation and verification`() {
    val signer: KeyPair = fromSecretSeed(SEP10_SIGNING_SEED)
    // create the request with the secret-key signer
    val request =
      ClientStatusCallbackHandler.buildHttpRequest(
        signer,
        "test_payload",
        "http://localhost:8092/callbacks",
      )

    val signature = request.header("Signature")
    // verify the signature with the public-key signer
    assertTrue(verifySignature(signature, "test_payload", "localhost:8092", signer))
    assertFalse(verifySignature(signature, "test_payload_bad", "localhost:8092", random()))
  }
}
