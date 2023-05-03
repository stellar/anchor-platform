package org.stellar.anchor.platform.service

import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.anchor.api.custody.GenerateDepositAddressResponse
import org.stellar.anchor.api.exception.CustodyException
import org.stellar.anchor.api.shared.SepDepositInfo
import org.stellar.anchor.platform.apiclient.CustodyApiClient
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep31Transaction

class DepositInfoGeneratorTest {

  companion object {
    private const val TX_ID = "123e4567-e89b-12d3-a456-426614174000"
    private const val ADDRESS = "testAccount"
    private const val MEMO = "MTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc="
    private const val MEMO_TYPE = "hash"
    private const val ASSET_ID = "USDC"
  }

  @Test
  fun test_sep24_selfGenerator_success() {
    val txn = JdbcSep24Transaction()
    txn.id = TX_ID
    txn.toAccount = ADDRESS
    val generator = Sep24DepositInfoGeneratorSelf()

    val actualInfo = generator.generate(txn)

    val expectedInfo = SepDepositInfo(ADDRESS, MEMO, MEMO_TYPE)

    assertEquals(expectedInfo, actualInfo)
  }

  @Test
  fun test_sep24_custodyGenerator_success() {
    val txn = JdbcSep24Transaction()
    txn.amountInAsset = ASSET_ID
    val custodyApiClient: CustodyApiClient = mockk()
    val generator = Sep24DepositInfoGeneratorCustody(custodyApiClient)

    val depositAddress = GenerateDepositAddressResponse(ADDRESS, MEMO, MEMO_TYPE)

    every { custodyApiClient.generateDepositAddress(ASSET_ID) } returns depositAddress

    val actualInfo = generator.generate(txn)

    val expectedInfo = SepDepositInfo(ADDRESS, MEMO, MEMO_TYPE)

    assertEquals(expectedInfo, actualInfo)
  }

  @Test
  fun test_sep24_custodyGenerator_error() {
    val txn = JdbcSep24Transaction()
    txn.amountInAsset = ASSET_ID
    val custodyApiClient: CustodyApiClient = mockk()
    val generator = Sep24DepositInfoGeneratorCustody(custodyApiClient)

    every { custodyApiClient.generateDepositAddress(ASSET_ID) } throws
      CustodyException("Custody exception")

    val exception = assertThrows<CustodyException> { generator.generate(txn) }

    Assertions.assertEquals("Custody exception", exception.message)
  }

  @Test
  fun test_sep31_selfGenerator_success() {
    val txn = JdbcSep31Transaction()
    txn.id = TX_ID
    txn.stellarAccountId = ADDRESS
    val generator = Sep31DepositInfoGeneratorSelf()

    val actualInfo = generator.generate(txn)

    val expectedInfo = SepDepositInfo(ADDRESS, MEMO, MEMO_TYPE)

    assertEquals(expectedInfo, actualInfo)
  }

  @Test
  fun test_sep31_selfCustody_success() {
    val txn = JdbcSep31Transaction()
    txn.amountInAsset = ASSET_ID
    val custodyApiClient: CustodyApiClient = mockk()
    val generator = Sep31DepositInfoGeneratorCustody(custodyApiClient)
    val depositAddress = GenerateDepositAddressResponse(ADDRESS, MEMO, MEMO_TYPE)

    every { custodyApiClient.generateDepositAddress(ASSET_ID) } returns depositAddress

    val actualInfo = generator.generate(txn)

    val expectedInfo = SepDepositInfo(ADDRESS, MEMO, MEMO_TYPE)

    assertEquals(expectedInfo, actualInfo)
  }

  @Test
  fun test_sep31_custodyGenerator_error() {
    val txn = JdbcSep31Transaction()
    txn.amountInAsset = ASSET_ID
    val custodyApiClient: CustodyApiClient = mockk()
    val generator = Sep31DepositInfoGeneratorCustody(custodyApiClient)

    every { custodyApiClient.generateDepositAddress(ASSET_ID) } throws
      CustodyException("Custody exception")

    val exception = assertThrows<CustodyException> { generator.generate(txn) }

    Assertions.assertEquals("Custody exception", exception.message)
  }
}
