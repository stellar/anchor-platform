package org.stellar.anchor.ledger

import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LedgerTransactionTest {
  private fun unresolvedInvokeOp() =
    LedgerTransaction.LedgerInvokeHostFunctionOperation.builder()
      .contractId("CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA")
      .id("1")
      .amount(BigInteger.ONE)
      .build()

  @Test
  fun `toString of an unresolved invoke-host-function operation does not throw`() {
    val op = unresolvedInvokeOp()

    assertThrows(IllegalStateException::class.java) { op.getAsset() }
    val text = assertDoesNotThrow<String> { op.toString() }
    assertTrue(text.contains("asset=null"))
  }

  @Test
  fun `equals and hashCode of an unresolved invoke-host-function operation do not throw`() {
    assertDoesNotThrow { assertEquals(unresolvedInvokeOp(), unresolvedInvokeOp()) }
    assertDoesNotThrow { unresolvedInvokeOp().hashCode() }
  }
}
