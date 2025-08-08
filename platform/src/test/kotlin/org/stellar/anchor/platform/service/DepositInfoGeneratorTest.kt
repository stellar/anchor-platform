package org.stellar.anchor.platform.service

import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.data.JdbcSep24Transaction
import org.stellar.anchor.platform.data.JdbcSep31Transaction

class DepositInfoGeneratorTest {

  companion object {
    private const val TX_ID = "123e4567-e89b-12d3-a456-426614174000"
    private const val ADDRESS = "testAccount"
    private const val MEMO = "12345="
    private const val MEMO_TYPE = "id"
    private const val ASSET_ID = "USDC"
  }

  @Test
  fun test_sep24_selfGenerator_success() {
    val txn = JdbcSep24Transaction()
    txn.id = TX_ID
    txn.toAccount = ADDRESS
    val generator = Sep24DepositInfoSelfGenerator()

    val actualInfo = generator.generate(txn)

    assertEquals(actualInfo.stellarAddress, ADDRESS)
    assertTrue(actualInfo.memo.toLongOrNull() != null)
    assertTrue(actualInfo.memoType == "id")
  }

  @Test
  fun test_sep31_selfGenerator_success() {
    val txn = JdbcSep31Transaction()
    txn.id = TX_ID
    txn.toAccount = ADDRESS
    val generator = Sep31DepositInfoSelfGenerator()

    val actualInfo = generator.generate(txn)

    assertEquals(actualInfo.stellarAddress, ADDRESS)
    assertTrue(actualInfo.memo.toLongOrNull() != null)
    assertTrue(actualInfo.memoType == "id")
  }
}
