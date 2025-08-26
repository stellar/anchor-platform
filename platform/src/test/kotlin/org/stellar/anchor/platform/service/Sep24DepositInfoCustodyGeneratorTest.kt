package org.stellar.anchor.platform.service

import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.custody.GenerateDepositAddressResponse
import org.stellar.anchor.api.shared.SepDepositInfo
import org.stellar.anchor.platform.apiclient.CustodyApiClient
import org.stellar.anchor.platform.data.JdbcSep24Transaction

class Sep24DepositInfoCustodyGeneratorTest {

  companion object {
    private const val ADDRESS = "testAccount"
    private const val MEMO = "MTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc="
    private const val MEMO_TYPE = "hash"
    private const val ASSET_ID = "USDC"
  }

  @Test
  fun test_sep24_custodyGenerator_success() {
    val txn = JdbcSep24Transaction()
    txn.amountInAsset = ASSET_ID
    val custodyApiClient: CustodyApiClient = mockk()
    val generator = Sep24DepositInfoCustodyGenerator(custodyApiClient)

    val depositAddress = GenerateDepositAddressResponse(ADDRESS, MEMO, MEMO_TYPE)

    every { custodyApiClient.generateDepositAddress(ASSET_ID) } returns depositAddress

    val actualInfo = generator.generate(txn)

    val expectedInfo = SepDepositInfo(ADDRESS, MEMO)

    assertEquals(expectedInfo, actualInfo)
  }
}
