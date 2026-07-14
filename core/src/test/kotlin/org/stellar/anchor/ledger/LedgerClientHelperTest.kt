package org.stellar.anchor.ledger

import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.LedgerException
import org.stellar.anchor.util.GsonUtils
import org.stellar.sdk.KeyPair
import org.stellar.sdk.StrKey
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

  private fun droppedOperation(): Operation =
    Operation.builder()
      .body(Operation.OperationBody.builder().discriminant(OperationType.MANAGE_DATA).build())
      .build()

  private fun contractEvent(
    from: String,
    to: String,
    assetStr: String,
    amount: BigInteger,
  ): ContractEvent {
    val topics =
      arrayOf(
        Scv.toSymbol("transfer"),
        Scv.toAddress(from),
        Scv.toAddress(to),
        Scv.toString(assetStr)
      )
    val body =
      ContractEvent.ContractEventBody.builder()
        .discriminant(0)
        .v0(
          ContractEvent.ContractEventBody.ContractEventV0.builder()
            .topics(topics)
            .data(Scv.toInt128(amount))
            .build()
        )
        .build()
    return ContractEvent.builder().type(ContractEventType.CONTRACT).body(body).build()
  }

  private fun withContractId(event: ContractEvent, contractIdStrKey: String): ContractEvent {
    event.contractID = ContractID(Hash(StrKey.decodeContract(contractIdStrKey)))
    return event
  }

  @Test
  fun `test getLedgerOperations synthesizes a sub-invocation transfer from a verified SAC`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val contractId = "CABZBKCMLL4U7ZYF2SJ2VIFZJQQR5LZPL6BH6W7PNSPVXVUT5VMQ27DR"
    val amount = BigInteger.valueOf(500_000L)

    val operations = arrayOf(droppedOperation())
    val parseResult = LedgerClientHelper.ParseResult(operations, fromAccount, null)
    val events = withContractId(contractEvent(fromAccount, toAccount, "native", amount), contractId)

    val nativeAsset = org.stellar.sdk.Asset.createNativeAsset().toXdr()
    val operationList =
      LedgerClientHelper.getLedgerOperations(
        1,
        1000L,
        parseResult,
        null,
        listOf(listOf(events)),
      ) { id ->
        if (id == contractId) nativeAsset else null
      }

    assertEquals(1, operationList.size)
    val invokeOp = operationList[0].invokeHostFunctionOperation
    assertEquals(fromAccount, invokeOp.from)
    assertEquals(toAccount, invokeOp.to)
    assertEquals(amount, invokeOp.amount)
    assertEquals(contractId, invokeOp.contractId)
  }

  @Test
  fun `test getLedgerOperations refuses to synthesize when the contract is not a resolvable SAC`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val contractId = "CABZBKCMLL4U7ZYF2SJ2VIFZJQQR5LZPL6BH6W7PNSPVXVUT5VMQ27DR"

    val operations = arrayOf(droppedOperation())
    val parseResult = LedgerClientHelper.ParseResult(operations, fromAccount, null)
    val events =
      withContractId(
        contractEvent(fromAccount, toAccount, "native", BigInteger.valueOf(500_000L)),
        contractId,
      )

    val operationList =
      LedgerClientHelper.getLedgerOperations(
        1,
        1000L,
        parseResult,
        null,
        listOf(listOf(events)),
      ) {
        null
      }

    assertEquals(0, operationList.size)
  }

  @Test
  fun `test getLedgerOperations refuses to synthesize when the event asset does not match the contract's canonical asset`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val contractId = "CABZBKCMLL4U7ZYF2SJ2VIFZJQQR5LZPL6BH6W7PNSPVXVUT5VMQ27DR"

    val operations = arrayOf(droppedOperation())
    val parseResult = LedgerClientHelper.ParseResult(operations, fromAccount, null)
    val events =
      withContractId(
        contractEvent(fromAccount, toAccount, "native", BigInteger.valueOf(500_000L)),
        contractId,
      )

    val usdcAsset =
      org.stellar.sdk.Asset.create("USDC:GDQOE23CFSUMSVQK4Y5JHPPYK73VYCNHZHA7ENKCV37P6SUEO6XQBKPP")
        .toXdr()
    val operationList =
      LedgerClientHelper.getLedgerOperations(
        1,
        1000L,
        parseResult,
        null,
        listOf(listOf(events)),
      ) { id ->
        if (id == contractId) usdcAsset else null
      }

    assertEquals(0, operationList.size)
  }

  @Test
  fun `test getLedgerOperations preserves an i128 amount above Long-MAX_VALUE without wrapping`() {
    val fromAccount = KeyPair.random().accountId
    val toAccount = KeyPair.random().accountId
    val contractId = "CABZBKCMLL4U7ZYF2SJ2VIFZJQQR5LZPL6BH6W7PNSPVXVUT5VMQ27DR"
    val hugeAmount = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(1_000_000L))

    val operations = arrayOf(droppedOperation())
    val parseResult = LedgerClientHelper.ParseResult(operations, fromAccount, null)
    val events =
      withContractId(contractEvent(fromAccount, toAccount, "native", hugeAmount), contractId)

    val nativeAsset = org.stellar.sdk.Asset.createNativeAsset().toXdr()
    val operationList =
      LedgerClientHelper.getLedgerOperations(
        1,
        1000L,
        parseResult,
        null,
        listOf(listOf(events)),
      ) { id ->
        if (id == contractId) nativeAsset else null
      }

    assertEquals(1, operationList.size)
    assertEquals(hugeAmount, operationList[0].invokeHostFunctionOperation.amount)
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
