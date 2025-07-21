package org.stellar.anchor.platform.service

import java.util.stream.Stream
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.stellar.anchor.platform.data.JdbcSep31Transaction

class Sep31DepositInfoSelfGeneratorTest {

  companion object {
    private const val TX_ID = "123e4567-e89b-12d3-a456-426614174000"
    private const val ADDRESS = "testAccount"
    private const val MEMO = "MTIzZTQ1NjctZTg5Yi0xMmQzLWE0NTYtNDI2NjE0MTc="
    private const val MEMO_TYPE = "hash"

    @JvmStatic
    fun assets(): Stream<Arguments> {
      return Stream.of(
        Arguments.of(
          "testId1",
          "GBJDTHT4562X2H37JMOE6IUTZZSDU6RYGYUNFYCHVFG3J4MYJIMU33HK",
          "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMHRlc3RJZDE="
        ),
        Arguments.of("testId2", null, "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMHRlc3RJZDI=")
      )
    }
  }

  @ParameterizedTest
  @MethodSource("assets")
  fun test_sep31_selfGenerator_success(txnId: String, address: String?, memo: String) {
    val txn = JdbcSep31Transaction()
    txn.id = txnId
    txn.toAccount = address
    val generator = Sep31DepositInfoSelfGenerator()

    val actualInfo = generator.generate(txn)

    assertEquals(actualInfo.stellarAddress, address)
    assertTrue(actualInfo.memo.toLongOrNull() != null)
    assertTrue(actualInfo.memoType == "id")
  }
}
