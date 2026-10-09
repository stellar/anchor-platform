package org.stellar.anchor.api.shared

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeeDetailsTest {
  private fun total(vararg amounts: String): String {
    val fee = FeeDetails("0", "stellar:native")
    amounts.forEachIndexed { i, a -> fee.addFeeDetail(FeeDescription("fee-$i", a)) }
    return fee.total
  }

  @Test
  fun `total keeps 7 decimals instead of truncating to 4`() {
    assertEquals("12.3456789", total("12.3456789"))
  }

  @Test
  fun `total sums high-precision details exactly`() {
    assertEquals("0.0000003", total("0.0000001", "0.0000002"))
  }

  @Test
  fun `total keeps at least 2 decimals`() {
    assertEquals("1.00", total("1"))
    assertEquals("1.50", total("1.5"))
  }

  @Test
  fun `total ignores the JVM default locale`() {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(Locale("pt", "BR"))
      assertEquals("12.3456789", total("12.3456789"))
    } finally {
      Locale.setDefault(previous)
    }
  }
}
