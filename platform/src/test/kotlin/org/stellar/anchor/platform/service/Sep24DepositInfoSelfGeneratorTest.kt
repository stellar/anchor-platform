package org.stellar.anchor.platform.service

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.data.JdbcSep24Transaction

class Sep24DepositInfoSelfGeneratorTest {

  companion object {
    private const val TX_ID = "123e4567-e89b-12d3-a456-426614174000"
    private const val ADDRESS = "testAccount"
    private const val CLIENT_ID_MEMO = "77713371337"
    private const val HASH_MEMO = "MTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc="
  }

  private fun withdrawal(memo: String?, memoType: String?): JdbcSep24Transaction {
    val txn = JdbcSep24Transaction()
    txn.id = TX_ID
    txn.toAccount = ADDRESS
    txn.memo = memo
    txn.memoType = memoType
    return txn
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
  }

  @Test
  fun `test sep24 selfGenerator replaces a client id memo with a minted one`() {
    val info = Sep24DepositInfoSelfGenerator().generate(withdrawal(CLIENT_ID_MEMO, "id"))

    assertEquals(ADDRESS, info.stellarAddress)
    assertNotEquals(CLIENT_ID_MEMO, info.memo)
    assertTrue(info.memo.toLongOrNull() != null)
  }

  @Test
  fun `test sep24 selfGenerator mints a different positive memo on each call`() {
    val generator = Sep24DepositInfoSelfGenerator()
    val txn = withdrawal(CLIENT_ID_MEMO, "id")

    val memos = (1..20).map { generator.generate(txn).memo }

    assertEquals(memos.size, memos.toSet().size)
    memos.forEach {
      val value = it.toLong()
      assertTrue(value in 1 until Long.MAX_VALUE, "memo $it is outside 1 until Long.MAX_VALUE")
    }
  }

  @Test
  fun `test sep24 selfGenerator mints an id memo for text hash and absent memo types`() {
    val generator = Sep24DepositInfoSelfGenerator()
    listOf(
        withdrawal("hello", "text"),
        withdrawal(HASH_MEMO, "hash"),
        withdrawal(CLIENT_ID_MEMO, null),
        withdrawal(null, null),
      )
      .forEach { txn ->
        val info = generator.generate(txn)

        assertEquals(ADDRESS, info.stellarAddress)
        assertTrue(info.memo.toLongOrNull() != null, "memo ${info.memo} is not an id memo")
        assertNotEquals(txn.memo, info.memo)
      }
  }
}
