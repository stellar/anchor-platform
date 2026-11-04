package org.stellar.anchor.ledger

import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.stellar.sdk.Account
import org.stellar.sdk.AssetTypeNative
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.Server
import org.stellar.sdk.StrKey
import org.stellar.sdk.TOID
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.requests.TransactionsRequestBuilder
import org.stellar.sdk.responses.TransactionResponse
import org.stellar.sdk.xdr.CryptoKeyType
import org.stellar.sdk.xdr.EnvelopeType
import org.stellar.sdk.xdr.FeeBumpTransaction
import org.stellar.sdk.xdr.FeeBumpTransactionEnvelope
import org.stellar.sdk.xdr.Int64
import org.stellar.sdk.xdr.MuxedAccount
import org.stellar.sdk.xdr.OperationResult
import org.stellar.sdk.xdr.OperationResultCode
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.TransactionEnvelope
import org.stellar.sdk.xdr.TransactionResult
import org.stellar.sdk.xdr.TransactionResultCode
import org.stellar.sdk.xdr.Uint256
import org.stellar.sdk.xdr.Uint64
import org.stellar.sdk.xdr.XdrUnsignedHyperInteger

/**
 * A payment from a transaction whose source is a muxed account (CAP-0027) must come back from both
 * ledger backends as an ordinary [LedgerTransaction] (ANCHOR-1319, HackerOne #4018874 / #4165277).
 */
class MuxedTransactionSourceTest {
  private val source = KeyPair.random().accountId
  private val destination = KeyPair.random().accountId
  private val txHash = "3c7311b015fa86eb218c8e9b93b3adbdd2ec5d3892f91a8a0c937de19d9d41c0"
  private val stroops = BigInteger.valueOf(125_000_000L)

  private fun muxedSource(): MuxedAccount =
    MuxedAccount.builder()
      .discriminant(CryptoKeyType.KEY_TYPE_MUXED_ED25519)
      .med25519(
        MuxedAccount.MuxedAccountMed25519.builder()
          .id(Uint64(XdrUnsignedHyperInteger(1234L)))
          .ed25519(Uint256.fromXdrByteArray(StrKey.decodeEd25519PublicKey(source)))
          .build()
      )
      .build()

  /** A one-payment V1 envelope whose transaction source is the muxed account above. */
  private fun muxedV1Envelope(): TransactionEnvelope {
    val transaction =
      TransactionBuilder(Account(source, 1L), Network.TESTNET)
        .addOperation(
          PaymentOperation.builder()
            .destination(destination)
            .asset(AssetTypeNative())
            .amount(BigDecimal("12.5"))
            .build()
        )
        .setBaseFee(100)
        .setTimeout(0L)
        .build()
    val envelope = TransactionEnvelope.fromXdrBase64(transaction.toEnvelopeXdrBase64())
    envelope.v1.tx.sourceAccount = muxedSource()
    return envelope
  }

  /** The same payment, wrapped in a fee bump whose inner transaction source is muxed. */
  private fun muxedFeeBumpEnvelope(): TransactionEnvelope =
    TransactionEnvelope.builder()
      .discriminant(EnvelopeType.ENVELOPE_TYPE_TX_FEE_BUMP)
      .feeBump(
        FeeBumpTransactionEnvelope.builder()
          .tx(
            FeeBumpTransaction.builder()
              .feeSource(
                MuxedAccount.builder()
                  .discriminant(CryptoKeyType.KEY_TYPE_ED25519)
                  .ed25519(
                    Uint256.fromXdrByteArray(
                      StrKey.decodeEd25519PublicKey(KeyPair.random().accountId)
                    )
                  )
                  .build()
              )
              .innerTx(
                FeeBumpTransaction.FeeBumpTransactionInnerTx.builder()
                  .discriminant(EnvelopeType.ENVELOPE_TYPE_TX)
                  .v1(muxedV1Envelope().v1)
                  .build()
              )
              .fee(Int64(200L))
              .ext(FeeBumpTransaction.FeeBumpTransactionExt.builder().discriminant(0).build())
              .build()
          )
          .signatures(arrayOf())
          .build()
      )
      .build()

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

  private fun horizonReturning(envelope: TransactionEnvelope): Horizon {
    val response = mockk<TransactionResponse>()
    every { response.envelopeXdr } returns envelope.toXdrBase64()
    every { response.pagingToken } returns TOID(100, 1, 0).toInt64().toString()
    every { response.ledger } returns 100L
    every { response.hash } returns txHash
    every { response.sourceAccount } returns source
    every { response.sourceAccountSequence } returns 2L
    every { response.createdAt } returns "2026-11-04T00:00:00Z"
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

  private fun assertSinglePaymentFromSource(txn: LedgerTransaction?) {
    assertNotNull(txn)
    assertEquals(1, txn!!.operations.size)
    val operation = txn.operations[0]
    assertEquals(OperationType.PAYMENT, operation.type)
    assertEquals(source, operation.paymentOperation.from)
    assertEquals(source, operation.paymentOperation.sourceAccount)
    assertEquals(destination, operation.paymentOperation.to)
    assertEquals(stroops, operation.paymentOperation.amount)
  }

  @Test
  fun `Horizon getTransaction returns a transaction whose V1 source is muxed`() {
    val txn = horizonReturning(muxedV1Envelope()).getTransaction(txHash)

    assertSinglePaymentFromSource(txn)
  }

  @Test
  fun `Horizon getTransaction returns a transaction whose fee bump inner source is muxed`() {
    val txn = horizonReturning(muxedFeeBumpEnvelope()).getTransaction(txHash)

    assertSinglePaymentFromSource(txn)
  }

  @Test
  fun `waitForTransactionAvailable returns a muxed source transaction from Horizon on the first attempt`() {
    // maxPollCount = 1: a null on the first attempt would end the wait with a LedgerException.
    val txn =
      LedgerClientHelper.waitForTransactionAvailable(
        horizonReturning(muxedV1Envelope()),
        txHash,
        30,
        1,
      )

    assertSinglePaymentFromSource(txn)
  }
}
