package org.stellar.anchor.ledger

import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.responses.SubmitTransactionAsyncResponse.TransactionStatus
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.SendTransactionStatus.*
import org.stellar.sdk.xdr.*
import org.stellar.sdk.xdr.CryptoKeyType.KEY_TYPE_ED25519
import org.stellar.sdk.xdr.EnvelopeType.*
import org.stellar.sdk.xdr.MemoType.MEMO_TEXT
import org.stellar.sdk.xdr.OperationType.PATH_PAYMENT_STRICT_RECEIVE
import org.stellar.sdk.xdr.SignerKeyType.*

internal class LedgerClientHelperTest {

  @Test
  fun `test getKeyTypeDiscriminant with valid types`() {
    assertEquals(
      SIGNER_KEY_TYPE_ED25519,
      LedgerClientHelper.getKeyTypeDiscriminant("ed25519_public_key"),
    )
    assertEquals(
      SIGNER_KEY_TYPE_PRE_AUTH_TX,
      LedgerClientHelper.getKeyTypeDiscriminant("preauth_tx"),
    )
    assertEquals(SIGNER_KEY_TYPE_HASH_X, LedgerClientHelper.getKeyTypeDiscriminant("sha256_hash"))
    assertEquals(
      SIGNER_KEY_TYPE_ED25519_SIGNED_PAYLOAD,
      LedgerClientHelper.getKeyTypeDiscriminant("ed25519_signed_payload"),
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

  @Test
  fun `test convert() with payment transaction`() {
    val operation = GsonUtils.getInstance().fromJson(testPaymentOpJson, Operation::class.java)

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
      )

    assertEquals(OperationType.PAYMENT, ledgerOperation.type)
    assertEquals(
      "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
      ledgerOperation.paymentOperation.from,
    )
    assertEquals(
      "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      ledgerOperation.paymentOperation.to,
    )
    assertEquals(1230L, ledgerOperation.paymentOperation.amount)
    assertEquals(
      "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
      ledgerOperation.paymentOperation.sourceAccount,
    )
  }

  @Test
  fun `test convert() with path payment transaction`() {
    val operation = GsonUtils.getInstance().fromJson(testPathPaymentOpJson, Operation::class.java)

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
      )

    assertEquals(PATH_PAYMENT_STRICT_RECEIVE, ledgerOperation.type)
    assertEquals(
      "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
      ledgerOperation.pathPaymentOperation.from,
    )
    assertEquals(
      "GDJLBYYKMCXNVVNABOE66NYXQGIA5AC5D223Z2KF6ZEYK4UBCA7FKLTG",
      ledgerOperation.pathPaymentOperation.to,
    )
    assertEquals(1230L, ledgerOperation.pathPaymentOperation.amount)
    assertEquals(
      "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
      ledgerOperation.pathPaymentOperation.sourceAccount,
    )
  }

  @Test
  fun `test convert() with unhandled type`() {
    val operation = GsonUtils.getInstance().fromJson(testUnhandledOpJson, Operation::class.java)
    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
      )

    assertNull(ledgerOperation)
  }

  @Test
  fun `test parseOperationAndSourceAccountAndMemo for ENVELOPE_TYPE_TX_V0`() {
    // Mock TransactionEnvelope
    val operations = arrayOf(Operation.builder().build())
    val memo = Memo.builder().discriminant(MEMO_TEXT).text(XdrString("test memo")).build()
    val txnEnv =
      TransactionEnvelope.builder()
        .discriminant(ENVELOPE_TYPE_TX_V0)
        .v0(
          TransactionV0Envelope.builder()
            .tx(
              TransactionV0.builder()
                .sourceAccountEd25519(Uint256.fromXdrByteArray(ByteArray(32) { 1 }))
                .memo(memo)
                .operations(operations)
                .build()
            )
            .build()
        )
        .build()

    // Call the method
    val result = LedgerClientHelper.parseOperationAndSourceAccountAndMemo(txnEnv, "testHash")

    // Verify the result
    assertNotNull(result)
    assertArrayEquals(operations, result.operations())
    assertEquals("GAAQCAIBAEAQCAIBAEAQCAIBAEAQCAIBAEAQCAIBAEAQCAIBAEAQDZ7H", result.sourceAccount())
    assertEquals(memo, result.memo())
    assertEquals(operations, result.operations())
  }

  @Test
  fun `test parseOperationAndSourceAccountAndMemo for ENVELOPE_TYPE_TX_V1`() {
    // Mock TransactionEnvelope
    val operations = arrayOf(Operation.builder().build())
    val memo = Memo.builder().discriminant(MEMO_TEXT).text(XdrString("test memo")).build()
    val txnEnv =
      TransactionEnvelope.builder()
        .discriminant(ENVELOPE_TYPE_TX)
        .v1(
          TransactionV1Envelope.builder()
            .tx(
              Transaction.builder()
                .sourceAccount(
                  MuxedAccount.builder()
                    .discriminant(KEY_TYPE_ED25519)
                    .ed25519(Uint256.fromXdrByteArray(ByteArray(32) { 1 }))
                    .build()
                )
                .memo(memo)
                .operations(operations)
                .build()
            )
            .build()
        )
        .build()

    // Call the method
    val result = LedgerClientHelper.parseOperationAndSourceAccountAndMemo(txnEnv, "testHash")

    // Verify the result
    assertNotNull(result)
    assertArrayEquals(operations, result.operations())
    assertEquals("GAAQCAIBAEAQCAIBAEAQCAIBAEAQCAIBAEAQCAIBAEAQCAIBAEAQDZ7H", result.sourceAccount())
    assertEquals(memo, result.memo())
    assertEquals(operations, result.operations())
  }

  @Test
  fun `test parseOperationAndSourceAccountAndMemo for ENVELOPE_TYPE_FEE_BUMP`() {
    // Mock TransactionEnvelope
    val operations = arrayOf(Operation.builder().build())
    val memo = Memo.builder().discriminant(MEMO_TEXT).text(XdrString("test memo")).build()
    val txnEnv =
      TransactionEnvelope.builder()
        .discriminant(ENVELOPE_TYPE_TX_FEE_BUMP)
        .feeBump(
          FeeBumpTransactionEnvelope.builder()
            .tx(
              FeeBumpTransaction.builder()
                .innerTx(
                  FeeBumpTransaction.FeeBumpTransactionInnerTx.builder()
                    .discriminant(ENVELOPE_TYPE_TX)
                    .v1(
                      TransactionV1Envelope.builder()
                        .tx(
                          Transaction.builder()
                            .sourceAccount(
                              MuxedAccount.builder()
                                .discriminant(KEY_TYPE_ED25519)
                                .ed25519(Uint256.fromXdrByteArray(ByteArray(32) { 1 }))
                                .build()
                            )
                            .memo(memo)
                            .operations(operations)
                            .build()
                        )
                        .build()
                    )
                    .build()
                )
                .build()
            )
            .build()
        )
        .build()

    // Call the method
    val result = LedgerClientHelper.parseOperationAndSourceAccountAndMemo(txnEnv, "testHash")

    // Verify the result
    assertNotNull(result)
    assertArrayEquals(operations, result.operations())
    assertEquals("GAAQCAIBAEAQCAIBAEAQCAIBAEAQCAIBAEAQCAIBAEAQCAIBAEAQDZ7H", result.sourceAccount())
    assertEquals(memo, result.memo())
    assertEquals(operations, result.operations())
  }
}

private val testPaymentOpJson =
  """
{
   "body":{
      "discriminant":"PAYMENT",
      "paymentOp":{
         "destination":{
            "discriminant":"KEY_TYPE_ED25519",
            "ed25519":{
               "uint256":"0rDjCmCu2tWgC4nvNxeBkA6AXR61vOlF9kmFcoEQPlU="
            }
         },
         "asset":{
            "discriminant":"ASSET_TYPE_NATIVE"
         },
         "amount":{
            "int64":1230
         }
      }
   }
}
"""

private val testPathPaymentOpJson =
  """
{
   "body":{
      "discriminant":"PATH_PAYMENT_STRICT_RECEIVE",
      "paymentOp":{
         "destination":{
            "discriminant":"KEY_TYPE_ED25519",
            "ed25519":{
               "uint256":"0rDjCmCu2tWgC4nvNxeBkA6AXR61vOlF9kmFcoEQPlU="
            }
         },
         "asset":{
            "discriminant":"ASSET_TYPE_NATIVE"
         },
         "amount":{
            "int64":1230
         }
      }
   }
}
"""

private val testUnhandledOpJson = """
{
   "body":{
      "discriminant":"CREATE_ACCOUNT"
   }
}
"""
