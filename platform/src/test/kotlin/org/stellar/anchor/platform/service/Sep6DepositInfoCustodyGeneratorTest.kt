package org.stellar.anchor.platform.service

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.custody.GenerateDepositAddressResponse
import org.stellar.anchor.api.shared.SepDepositInfo
import org.stellar.anchor.platform.apiclient.CustodyApiClient
import org.stellar.anchor.platform.data.JdbcSep6Transaction

class Sep6DepositInfoCustodyGeneratorTest {
  companion object {
    private const val ADDRESS = "testAccount"
    private const val MEMO = "MTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc="
    private const val MEMO_TYPE = "hash"
    private const val ASSET_ID = "USDC"
  }

  @MockK(relaxed = true) lateinit var custodyApiClient: CustodyApiClient

  private lateinit var generator: Sep6DepositInfoCustodyGenerator

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    generator = Sep6DepositInfoCustodyGenerator(custodyApiClient)
  }

  @Test
  fun test_sep6_custodyGenerator_success() {
    val txn = JdbcSep6Transaction()
    txn.amountInAsset = ASSET_ID

    every { custodyApiClient.generateDepositAddress(ASSET_ID) } returns
      GenerateDepositAddressResponse(ADDRESS, MEMO, MEMO_TYPE)

    val result = generator.generate(txn)
    val expected = SepDepositInfo(ADDRESS, MEMO)
    assertEquals(expected, result)
  }
}
