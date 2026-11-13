package org.stellar.anchor.platform.utils

import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation
import org.stellar.anchor.platform.data.JdbcSep31Transaction
import org.stellar.anchor.platform.observer.stellar.SacToAssetMapper
import org.stellar.sdk.Asset
import org.stellar.sdk.xdr.AssetType
import org.stellar.sdk.xdr.OperationType

class PaymentHelperTest {
  private val contractId = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"

  @Test
  fun `resolveSacAsset returns the asset of a genuine SAC`() {
    val mapper = mockk<SacToAssetMapper>()
    every { mapper.getAssetFromSac(contractId) } returns Asset.createNativeAsset().toXdr()

    assertEquals(
      Asset.createNativeAsset().toXdr(),
      PaymentHelper.resolveSacAsset(mapper, contractId)
    )
  }

  @Test
  fun `resolveSacAsset returns null instead of failing the notification when the lookup fails`() {
    val mapper = mockk<SacToAssetMapper>()
    every { mapper.getAssetFromSac(contractId) } throws IllegalArgumentException("no rpc url")

    assertNull(PaymentHelper.resolveSacAsset(mapper, contractId))
  }

  private fun payment(id: String, amount: Long) =
    LedgerOperation.builder()
      .type(OperationType.PAYMENT)
      .paymentOperation(
        LedgerPaymentOperation.builder()
          .id(id)
          .amount(BigInteger.valueOf(amount))
          .asset(
            org.stellar.sdk.xdr.Asset.builder().discriminant(AssetType.ASSET_TYPE_NATIVE).build()
          )
          .from("testFrom")
          .to("testTo")
          .build()
      )
      .build()

  private fun ledgerTransaction(hash: String, vararg operations: LedgerOperation) =
    LedgerTransaction.builder()
      .hash(hash)
      .createdAt(Instant.parse("2023-05-10T10:18:20Z"))
      .envelopeXdr("envelope-$hash")
      .operations(operations.toList())
      .build()

  @Test
  fun `addStellarTransaction merges a repeated hash into one entry`() {
    val txn = JdbcSep31Transaction()
    PaymentHelper.addStellarTransaction(null, ledgerTransaction("hash-1", payment("1", 10)), txn)
    PaymentHelper.addStellarTransaction(
      null,
      ledgerTransaction("hash-1", payment("1", 10), payment("2", 20)),
      txn,
    )

    assertEquals(listOf("hash-1"), txn.stellarTransactions.map { it.id })
    assertEquals(listOf("1", "2"), txn.stellarTransactions[0].payments.map { it.id })
    assertEquals("hash-1", txn.stellarTransactionId)

    // a payment of the earlier entry that the later call does not carry is kept
    PaymentHelper.addStellarTransaction(null, ledgerTransaction("hash-1", payment("3", 30)), txn)
    assertEquals(listOf("hash-1"), txn.stellarTransactions.map { it.id })
    assertEquals(listOf("1", "2", "3"), txn.stellarTransactions[0].payments.map { it.id })
  }

  @Test
  fun `addStellarTransaction appends an entry for a new hash`() {
    val txn = JdbcSep31Transaction()
    PaymentHelper.addStellarTransaction(null, ledgerTransaction("hash-1", payment("1", 10)), txn)
    PaymentHelper.addStellarTransaction(null, ledgerTransaction("hash-2", payment("3", 30)), txn)

    assertEquals(listOf("hash-1", "hash-2"), txn.stellarTransactions.map { it.id })
    assertEquals("hash-2", txn.stellarTransactionId)
    assertEquals(Instant.parse("2023-05-10T10:18:20Z"), txn.transferReceivedAt)
  }
}
