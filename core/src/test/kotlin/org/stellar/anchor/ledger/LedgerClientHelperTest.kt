package org.stellar.anchor.ledger

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.sdk.responses.SubmitTransactionAsyncResponse.*
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.SendTransactionStatus.*
import org.stellar.sdk.xdr.SignerKeyType.*

internal class LedgerClientHelperTest {

  @Test
  fun `test getKeyTypeDiscriminant with valid types`() {
    assertEquals(
      SIGNER_KEY_TYPE_ED25519,
      LedgerClientHelper.getKeyTypeDiscriminant("ed25519_public_key")
    )
    assertEquals(
      SIGNER_KEY_TYPE_PRE_AUTH_TX,
      LedgerClientHelper.getKeyTypeDiscriminant("preauth_tx")
    )
    assertEquals(SIGNER_KEY_TYPE_HASH_X, LedgerClientHelper.getKeyTypeDiscriminant("sha256_hash"))
    assertEquals(
      SIGNER_KEY_TYPE_ED25519_SIGNED_PAYLOAD,
      LedgerClientHelper.getKeyTypeDiscriminant("ed25519_signed_payload")
    )
  }

  @Test
  fun `test getKeyTypeDiscriminant with invalid type`() {
    val exception =
      assertThrows<IllegalArgumentException> {
        LedgerClientHelper.getKeyTypeDiscriminant("invalid_type")
      }
    assertEquals("Invalid signer key type: invalid_type", exception.message)
  }

  @Test
  fun `test convert() with transaction status`() {
    assertEquals(PENDING, LedgerClientHelper.convert(TransactionStatus.PENDING))
    assertEquals(ERROR, LedgerClientHelper.convert(TransactionStatus.ERROR))
    assertEquals(DUPLICATE, LedgerClientHelper.convert(TransactionStatus.DUPLICATE))
    assertEquals(TRY_AGAIN_LATER, LedgerClientHelper.convert(TransactionStatus.TRY_AGAIN_LATER))
  }
}
