package org.stellar.anchor.ledger

import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.exception.LedgerDecodeException
import org.stellar.sdk.Account
import org.stellar.sdk.Address
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.Server
import org.stellar.sdk.SorobanDataBuilder
import org.stellar.sdk.TOID
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.operations.InvokeHostFunctionOperation
import org.stellar.sdk.requests.TransactionsRequestBuilder
import org.stellar.sdk.responses.TransactionResponse
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.ContractDataDurability
import org.stellar.sdk.xdr.LedgerEntryType
import org.stellar.sdk.xdr.LedgerKey
import org.stellar.sdk.xdr.OperationResult
import org.stellar.sdk.xdr.OperationResultCode
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.SCString
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType
import org.stellar.sdk.xdr.TransactionResult
import org.stellar.sdk.xdr.TransactionResultCode
import org.stellar.sdk.xdr.XdrString

class LedgerDecodeTest {
  private val sac = "CDLZFC3SYJYDZT7K67VZ75HPJVIEUVNIXF47ZG2FB2RMQQVU2HHGCYSC"
  private val txHash = "3c7311b015fa86eb218c8e9b93b3adbdd2ec5d3892f91a8a0c937de19d9d41c0"
  private val placeholder = "poison"

  private fun envelopeWithFootprintKey(key: SCVal): String {
    val source = KeyPair.random()
    val destination = KeyPair.random().accountId
    val operation =
      InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
          sac,
          "transfer",
          listOf(
            Scv.toAddress(source.accountId),
            Scv.toAddress(destination),
            Scv.toInt128(BigInteger.ONE),
          ),
        )
        .build()
    val footprintKey =
      LedgerKey.builder()
        .discriminant(LedgerEntryType.CONTRACT_DATA)
        .contractData(
          LedgerKey.LedgerKeyContractData.builder()
            .contract(Address(sac).toSCAddress())
            .key(key)
            .durability(ContractDataDurability.PERSISTENT)
            .build()
        )
        .build()
    val transaction =
      TransactionBuilder(Account(source.accountId, 1L), Network.TESTNET)
        .addOperation(operation)
        .setBaseFee(100)
        .setTimeout(0L)
        .setSorobanData(SorobanDataBuilder().setReadOnly(listOf(footprintKey)).build())
        .build()
    transaction.sign(source)
    return transaction.toEnvelopeXdrBase64()
  }

  private fun protocol28Envelope(): String =
    envelopeWithFootprintKey(
      SCVal.builder()
        .discriminant(SCValType.SCV_EXECUTABLE_TAG)
        .executable_tag(SCString(XdrString(placeholder)))
        .build()
    )

  private fun unknownArmEnvelope(): String {
    val stringKey = Scv.toString(placeholder)
    val bytes = Base64.getDecoder().decode(envelopeWithFootprintKey(stringKey))
    val needle = stringKey.toXdrByteArray()
    val at = indexOf(bytes, needle)
    check(at >= 0)
    bytes[at + 3] = 99
    return Base64.getEncoder().encodeToString(bytes)
  }

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return i
    }
    return -1
  }

  private fun successResult(): TransactionResult =
    TransactionResult.builder()
      .result(
        TransactionResult.TransactionResultResult.builder()
          .discriminant(TransactionResultCode.txSUCCESS)
          .results(
            arrayOf(OperationResult.builder().discriminant(OperationResultCode.opINNER).build())
          )
          .build()
      )
      .build()

  private fun horizonReturning(envelopeXdr: String): Horizon {
    val response = mockk<TransactionResponse>()
    every { response.envelopeXdr } returns envelopeXdr
    every { response.pagingToken } returns TOID(100, 1, 0).toInt64().toString()
    every { response.ledger } returns 100L
    every { response.hash } returns txHash
    every { response.sourceAccount } returns KeyPair.random().accountId
    every { response.sourceAccountSequence } returns 2L
    every { response.createdAt } returns "2026-09-23T00:00:00Z"
    every { response.parseResultXdr() } returns successResult()

    val requestBuilder = mockk<TransactionsRequestBuilder>()
    every { requestBuilder.transaction(txHash) } returns response
    val server = mockk<Server>()
    every { server.transactions() } returns requestBuilder

    val horizon = mockk<Horizon>()
    every { horizon.server } returns server
    every { horizon.getTransaction(txHash) } answers { callOriginal() }
    return horizon
  }

  @Test
  fun `the protocol 28 executable tag decodes`() {
    val envelope = org.stellar.sdk.xdr.TransactionEnvelope.fromXdrBase64(protocol28Envelope())

    val key = envelope.v1.tx.ext.sorobanData.resources.footprint.readOnly[0].contractData.key
    assertEquals(SCValType.SCV_EXECUTABLE_TAG, key.discriminant)
  }

  @Test
  fun `Horizon getTransaction parses a transaction carrying the protocol 28 executable tag`() {
    val txn = horizonReturning(protocol28Envelope()).getTransaction(txHash)

    assertNotNull(txn)
    assertEquals(1, txn.operations.size)
    assertEquals(OperationType.INVOKE_HOST_FUNCTION, txn.operations[0].type)
  }

  @Test
  fun `Horizon getTransaction reports an envelope the SDK cannot decode as LedgerDecodeException`() {
    val horizon = horizonReturning(unknownArmEnvelope())

    assertThrows<LedgerDecodeException> { horizon.getTransaction(txHash) }
  }

  @Test
  fun `StellarRpc reports an envelope the SDK cannot decode as LedgerDecodeException`() {
    val response = mockk<GetTransactionResponse>()
    every { response.envelopeXdr } returns unknownArmEnvelope()
    every { response.txHash } returns txHash

    assertThrows<LedgerDecodeException> { StellarRpc.fromGetTransactionResponse(response) }
  }

  @Test
  fun `StellarRpc parses a transaction carrying the protocol 28 executable tag`() {
    val response = mockk<GetTransactionResponse>()
    every { response.envelopeXdr } returns protocol28Envelope()
    every { response.txHash } returns txHash
    every { response.applicationOrder } returns 1
    every { response.ledger } returns 100L
    every { response.createdAt } returns 1_790_000_000L
    every { response.parseResultXdr() } returns successResult()

    val txn = StellarRpc.fromGetTransactionResponse(response)

    assertNotNull(txn)
    assertEquals(OperationType.INVOKE_HOST_FUNCTION, txn.operations[0].type)
  }

  @Test
  fun `isInvokeHostFunctionOperation returns false instead of throwing on an undecodable envelope`() {
    val txn = LedgerTransaction.builder().hash(txHash).envelopeXdr(unknownArmEnvelope()).build()

    assertEquals(false, LedgerClientHelper.isInvokeHostFunctionOperation(txn, 0))
  }
}
