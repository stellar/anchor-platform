package org.stellar.anchor.ledger

import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.LedgerException
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.Address
import org.stellar.sdk.KeyPair
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.SendTransactionStatus.*
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.*
import org.stellar.sdk.xdr.CryptoKeyType.KEY_TYPE_ED25519
import org.stellar.sdk.xdr.EnvelopeType.*
import org.stellar.sdk.xdr.MemoType.MEMO_TEXT
import org.stellar.sdk.xdr.OperationType.PATH_PAYMENT_STRICT_RECEIVE
import org.stellar.sdk.xdr.OperationType.PATH_PAYMENT_STRICT_SEND
import org.stellar.sdk.xdr.SignerKeyType.*

internal class LedgerClientHelperTest {
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
        null,
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
    assertEquals(BigInteger.valueOf(1230L), ledgerOperation.paymentOperation.amount)
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
        null,
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
    assertEquals(BigInteger.valueOf(1230L), ledgerOperation.pathPaymentOperation.amount)
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
        null,
      )

    assertNull(ledgerOperation)
  }

  @Test
  fun `test convert() with muxed destination payment returns M-address`() {
    val operation = GsonUtils.getInstance().fromJson(testMuxedPaymentOpJson, Operation::class.java)

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        null,
      )

    assertEquals(OperationType.PAYMENT, ledgerOperation.type)
    assertNotNull(ledgerOperation.paymentOperation.to)
    assertTrue(ledgerOperation.paymentOperation.to.startsWith("M"))
  }

  @Test
  fun `test convert() with muxed destination path payment strict receive returns M-address`() {
    val operation =
      GsonUtils.getInstance()
        .fromJson(testMuxedPathPaymentStrictReceiveOpJson, Operation::class.java)

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        null,
      )

    assertEquals(PATH_PAYMENT_STRICT_RECEIVE, ledgerOperation.type)
    assertNotNull(ledgerOperation.pathPaymentOperation.to)
    assertTrue(ledgerOperation.pathPaymentOperation.to.startsWith("M"))
  }

  @Test
  fun `test convert() with muxed destination path payment strict send returns M-address`() {
    val operation =
      GsonUtils.getInstance().fromJson(testMuxedPathPaymentStrictSendOpJson, Operation::class.java)

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        buildStrictSendSuccessResult(1230L),
      )

    assertEquals(PATH_PAYMENT_STRICT_SEND, ledgerOperation.type)
    assertNotNull(ledgerOperation.pathPaymentOperation.to)
    assertTrue(ledgerOperation.pathPaymentOperation.to.startsWith("M"))
    assertEquals(BigInteger.valueOf(1230L), ledgerOperation.pathPaymentOperation.amount)
  }

  @Test
  fun `test convert() with path payment strict send uses the operation result amount, not sendAmount`() {
    // testMuxedPathPaymentStrictSendOpJson has sendAmount=1230; the operation result reports a
    // completely different received amount to prove sendAmount is never used.
    val operation =
      GsonUtils.getInstance().fromJson(testMuxedPathPaymentStrictSendOpJson, Operation::class.java)

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        buildStrictSendSuccessResult(42L),
      )

    assertEquals(BigInteger.valueOf(42L), ledgerOperation.pathPaymentOperation.amount)
  }

  @Test
  fun `test convert() with path payment strict send throws LedgerException when operation result is null`() {
    val operation =
      GsonUtils.getInstance().fromJson(testMuxedPathPaymentStrictSendOpJson, Operation::class.java)

    assertThrows(LedgerException::class.java) {
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        null,
      )
    }
  }

  @Test
  fun `test convert() with path payment strict send throws LedgerException when operation result is not success`() {
    val operation =
      GsonUtils.getInstance().fromJson(testMuxedPathPaymentStrictSendOpJson, Operation::class.java)
    val opResult =
      OperationResult.builder()
        .discriminant(OperationResultCode.opINNER)
        .tr(
          OperationResult.OperationResultTr.builder()
            .discriminant(PATH_PAYMENT_STRICT_SEND)
            .pathPaymentStrictSendResult(
              PathPaymentStrictSendResult.builder()
                .discriminant(PathPaymentStrictSendResultCode.PATH_PAYMENT_STRICT_SEND_UNDERFUNDED)
                .build()
            )
            .build()
        )
        .build()

    assertThrows(LedgerException::class.java) {
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        opResult,
      )
    }
  }

  @Test
  fun `test convert() with path payment strict send throws LedgerException when operation result is for a different operation type`() {
    val operation =
      GsonUtils.getInstance().fromJson(testMuxedPathPaymentStrictSendOpJson, Operation::class.java)
    val opResult =
      OperationResult.builder()
        .discriminant(OperationResultCode.opINNER)
        .tr(OperationResult.OperationResultTr.builder().discriminant(OperationType.PAYMENT).build())
        .build()

    assertThrows(LedgerException::class.java) {
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        opResult,
      )
    }
  }

  @Test
  fun `test convert() with path payment strict send throws LedgerException when success is present but last is missing`() {
    val operation =
      GsonUtils.getInstance().fromJson(testMuxedPathPaymentStrictSendOpJson, Operation::class.java)
    val opResult =
      OperationResult.builder()
        .discriminant(OperationResultCode.opINNER)
        .tr(
          OperationResult.OperationResultTr.builder()
            .discriminant(PATH_PAYMENT_STRICT_SEND)
            .pathPaymentStrictSendResult(
              PathPaymentStrictSendResult.builder()
                .discriminant(PathPaymentStrictSendResultCode.PATH_PAYMENT_STRICT_SEND_SUCCESS)
                .success(
                  PathPaymentStrictSendResult.PathPaymentStrictSendResultSuccess.builder()
                    .offers(arrayOf())
                    .build()
                )
                .build()
            )
            .build()
        )
        .build()

    assertThrows(LedgerException::class.java) {
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        opResult,
      )
    }
  }

  @Test
  fun `test convert() with path payment strict send throws LedgerException when last is present but amount is missing`() {
    val operation =
      GsonUtils.getInstance().fromJson(testMuxedPathPaymentStrictSendOpJson, Operation::class.java)
    val opResult =
      OperationResult.builder()
        .discriminant(OperationResultCode.opINNER)
        .tr(
          OperationResult.OperationResultTr.builder()
            .discriminant(PATH_PAYMENT_STRICT_SEND)
            .pathPaymentStrictSendResult(
              PathPaymentStrictSendResult.builder()
                .discriminant(PathPaymentStrictSendResultCode.PATH_PAYMENT_STRICT_SEND_SUCCESS)
                .success(
                  PathPaymentStrictSendResult.PathPaymentStrictSendResultSuccess.builder()
                    .offers(arrayOf())
                    .last(SimplePaymentResult.builder().build())
                    .build()
                )
                .build()
            )
            .build()
        )
        .build()

    assertThrows(LedgerException::class.java) {
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        opResult,
      )
    }
  }

  @Test
  fun `test convert() with invoke host function transfer call`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val contractId = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"
    val operation =
      buildInvokeHostFunctionOperation(contractId, "transfer", fromAccount, toAccount, 500L)

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        null,
      )

    assertEquals(OperationType.INVOKE_HOST_FUNCTION, ledgerOperation.type)
    assertEquals(contractId, ledgerOperation.invokeHostFunctionOperation.contractId)
    assertEquals("transfer", ledgerOperation.invokeHostFunctionOperation.hostFunction)
    assertEquals(fromAccount, ledgerOperation.invokeHostFunctionOperation.from)
    assertEquals(toAccount, ledgerOperation.invokeHostFunctionOperation.to)
    assertEquals(BigInteger.valueOf(500L), ledgerOperation.invokeHostFunctionOperation.amount)
  }

  @Test
  fun `test convert() with invoke host function call under a non-transfer entrypoint`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val contractId = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"
    val operation =
      buildInvokeHostFunctionOperation(contractId, "pay", fromAccount, toAccount, 500L)

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        null,
      )

    assertNotNull(ledgerOperation)
    assertEquals(OperationType.INVOKE_HOST_FUNCTION, ledgerOperation.type)
    assertEquals("pay", ledgerOperation.invokeHostFunctionOperation.hostFunction)
    assertEquals(fromAccount, ledgerOperation.invokeHostFunctionOperation.from)
    assertEquals(toAccount, ledgerOperation.invokeHostFunctionOperation.to)
    assertEquals(BigInteger.valueOf(500L), ledgerOperation.invokeHostFunctionOperation.amount)
  }

  @Test
  fun `test convert() with invoke host function call carrying too few arguments returns null`() {
    val contractId = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"
    val operation =
      buildInvokeHostFunctionOperationWithArgs(
        contractId,
        "swap",
        arrayOf(Scv.toAddress(KeyPair.random().accountId)),
      )

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        null,
      )

    assertNull(ledgerOperation)
  }

  @Test
  fun `test convert() with invoke host function call carrying wrong argument types returns null`() {
    val contractId = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"
    val operation =
      buildInvokeHostFunctionOperationWithArgs(
        contractId,
        "execute",
        arrayOf(
          Scv.toAddress(KeyPair.random().accountId),
          Scv.toAddress(KeyPair.random().accountId),
          Scv.toString("not-an-amount"),
        ),
      )

    val ledgerOperation =
      LedgerClientHelper.convert(
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        1708638L,
        5,
        1,
        operation,
        null,
      )

    assertNull(ledgerOperation)
  }

  @Test
  fun `test parseOperationResults returns results for txSUCCESS`() {
    val opResults =
      arrayOf(OperationResult.builder().discriminant(OperationResultCode.opINNER).build())
    val txResult =
      TransactionResult.builder()
        .result(
          TransactionResult.TransactionResultResult.builder()
            .discriminant(TransactionResultCode.txSUCCESS)
            .results(opResults)
            .build()
        )
        .build()

    val result = LedgerClientHelper.parseOperationResults(txResult, "testHash")

    assertArrayEquals(opResults, result)
  }

  @Test
  fun `test parseOperationResults unwraps inner result for txFEE_BUMP_INNER_SUCCESS`() {
    val opResults =
      arrayOf(OperationResult.builder().discriminant(OperationResultCode.opINNER).build())
    val txResult =
      TransactionResult.builder()
        .result(
          TransactionResult.TransactionResultResult.builder()
            .discriminant(TransactionResultCode.txFEE_BUMP_INNER_SUCCESS)
            .innerResultPair(
              InnerTransactionResultPair.builder()
                .result(
                  InnerTransactionResult.builder()
                    .result(
                      InnerTransactionResult.InnerTransactionResultResult.builder()
                        .discriminant(TransactionResultCode.txSUCCESS)
                        .results(opResults)
                        .build()
                    )
                    .build()
                )
                .build()
            )
            .build()
        )
        .build()

    val result = LedgerClientHelper.parseOperationResults(txResult, "testHash")

    assertArrayEquals(opResults, result)
  }

  @Test
  fun `test parseOperationResults returns null for txFAILED`() {
    val txResult =
      TransactionResult.builder()
        .result(
          TransactionResult.TransactionResultResult.builder()
            .discriminant(TransactionResultCode.txFAILED)
            .build()
        )
        .build()

    val result = LedgerClientHelper.parseOperationResults(txResult, "testHash")

    assertNull(result)
  }

  @Test
  fun `test getLedgerOperations throws LedgerException when operationResults length does not match operations length`() {
    val operations = arrayOf(Operation.builder().build(), Operation.builder().build())
    val parseResult =
      LedgerClientHelper.ParseResult(
        operations,
        "GABCKCYPAGDDQMSCTMSBO7C2L34NU3XXCW7LR4VVSWCCXMAJY3B4YCZP",
        null,
      )
    val operationResults =
      arrayOf(OperationResult.builder().discriminant(OperationResultCode.opINNER).build())

    assertThrows(LedgerException::class.java) {
      LedgerClientHelper.getLedgerOperations(5, 1708638L, parseResult, operationResults)
    }
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

private fun buildInvokeHostFunctionOperation(
  contractId: String,
  functionName: String,
  fromAccount: String,
  toAccount: String,
  amount: Long,
): Operation {
  return buildInvokeHostFunctionOperationWithArgs(
    contractId,
    functionName,
    arrayOf(
      Scv.toAddress(fromAccount),
      Scv.toAddress(toAccount),
      Scv.toInt128(BigInteger.valueOf(amount))
    ),
  )
}

private fun buildInvokeHostFunctionOperationWithArgs(
  contractId: String,
  functionName: String,
  args: Array<SCVal>,
): Operation {
  val invokeContractArgs =
    InvokeContractArgs.builder()
      .contractAddress(Address(contractId).toSCAddress())
      .functionName(SCSymbol(XdrString(functionName)))
      .args(args)
      .build()
  val hostFunction =
    HostFunction.builder()
      .discriminant(HostFunctionType.HOST_FUNCTION_TYPE_INVOKE_CONTRACT)
      .invokeContract(invokeContractArgs)
      .build()
  val invokeHostFunctionOp = InvokeHostFunctionOp.builder().hostFunction(hostFunction).build()
  val body =
    Operation.OperationBody.builder()
      .discriminant(OperationType.INVOKE_HOST_FUNCTION)
      .invokeHostFunctionOp(invokeHostFunctionOp)
      .build()
  return Operation.builder().body(body).build()
}

private fun buildStrictSendSuccessResult(receivedAmount: Long): OperationResult {
  val destination =
    AccountID(
      PublicKey.builder()
        .discriminant(PublicKeyType.PUBLIC_KEY_TYPE_ED25519)
        .ed25519(Uint256.fromXdrByteArray(ByteArray(32) { 1 }))
        .build()
    )
  val simplePaymentResult =
    SimplePaymentResult.builder()
      .destination(destination)
      .asset(Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build())
      .amount(Int64(receivedAmount))
      .build()
  val success =
    PathPaymentStrictSendResult.PathPaymentStrictSendResultSuccess.builder()
      .offers(arrayOf())
      .last(simplePaymentResult)
      .build()
  val pathPaymentStrictSendResult =
    PathPaymentStrictSendResult.builder()
      .discriminant(PathPaymentStrictSendResultCode.PATH_PAYMENT_STRICT_SEND_SUCCESS)
      .success(success)
      .build()
  val tr =
    OperationResult.OperationResultTr.builder()
      .discriminant(PATH_PAYMENT_STRICT_SEND)
      .pathPaymentStrictSendResult(pathPaymentStrictSendResult)
      .build()
  return OperationResult.builder().discriminant(OperationResultCode.opINNER).tr(tr).build()
}

private const val testPaymentOpJson =
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

private const val testPathPaymentOpJson =
  """
{
   "body":{
      "discriminant":"PATH_PAYMENT_STRICT_RECEIVE",
      "pathPaymentStrictReceiveOp":{
         "sendAsset":{
             "discriminant":"ASSET_TYPE_NATIVE"
         },
            "sendMax":{
                "int64":1230
            },
         "destination":{
            "discriminant":"KEY_TYPE_ED25519",
            "ed25519":{
               "uint256":"0rDjCmCu2tWgC4nvNxeBkA6AXR61vOlF9kmFcoEQPlU="
            }
         },
         "destAsset":{
            "discriminant":"ASSET_TYPE_NATIVE"
         },
         "destAmount":{
            "int64":1230
         }
      }
   }
}
"""

private const val testMuxedPaymentOpJson =
  """
{
   "body":{
      "discriminant":"PAYMENT",
      "paymentOp":{
         "destination":{
            "discriminant":"KEY_TYPE_MUXED_ED25519",
            "med25519":{
               "id":{"uint64":{"number":12345}},
               "ed25519":{"uint256":"0rDjCmCu2tWgC4nvNxeBkA6AXR61vOlF9kmFcoEQPlU="}
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

private const val testMuxedPathPaymentStrictReceiveOpJson =
  """
{
   "body":{
      "discriminant":"PATH_PAYMENT_STRICT_RECEIVE",
      "pathPaymentStrictReceiveOp":{
         "sendAsset":{
             "discriminant":"ASSET_TYPE_NATIVE"
         },
         "sendMax":{
             "int64":1230
         },
         "destination":{
            "discriminant":"KEY_TYPE_MUXED_ED25519",
            "med25519":{
               "id":{"uint64":{"number":12345}},
               "ed25519":{"uint256":"0rDjCmCu2tWgC4nvNxeBkA6AXR61vOlF9kmFcoEQPlU="}
            }
         },
         "destAsset":{
            "discriminant":"ASSET_TYPE_NATIVE"
         },
         "destAmount":{
            "int64":1230
         }
      }
   }
}
"""

private const val testMuxedPathPaymentStrictSendOpJson =
  """
{
   "body":{
      "discriminant":"PATH_PAYMENT_STRICT_SEND",
      "pathPaymentStrictSendOp":{
         "sendAsset":{
             "discriminant":"ASSET_TYPE_NATIVE"
         },
         "sendAmount":{
             "int64":1230
         },
         "destination":{
            "discriminant":"KEY_TYPE_MUXED_ED25519",
            "med25519":{
               "id":{"uint64":{"number":12345}},
               "ed25519":{"uint256":"0rDjCmCu2tWgC4nvNxeBkA6AXR61vOlF9kmFcoEQPlU="}
            }
         },
         "destAsset":{
            "discriminant":"ASSET_TYPE_NATIVE"
         },
         "destMin":{
            "int64":1230
         }
      }
   }
}
"""

private const val testUnhandledOpJson =
  """
{
   "body":{
      "discriminant":"CREATE_ACCOUNT"
   }
}
"""
