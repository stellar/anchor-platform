package org.stellar.anchor.platform.service

import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.data.JdbcSep24Transaction

class Sep24DepositInfoSelfGeneratorTest {

  companion object {
    private const val TX_ID = "123e4567-e89b-12d3-a456-426614174000"
    private const val ADDRESS = "testAccount"
    private const val MEMO = "MTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc="
    private const val MEMO_TYPE = "hash"
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
}
