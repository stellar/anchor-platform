package org.stellar.anchor.util

import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MathHelperTest {
  @ParameterizedTest
  @CsvSource(
    value = [",", "0,0", "0.1,0.1000000", "1,1", "1,1.000000", "-1,-1", "-1,-1.0000000", "500,5e2"]
  )
  fun `test equalsAsDecimals true`(valueA: String?, valueB: String?) {
    assertTrue(MathHelper.equalsAsDecimals(valueA, valueB))
  }

  @ParameterizedTest
  @CsvSource(value = ["0,-0.0000001", "-0.1,0.1000000", "0,1.000000", "0,", ",0"])
  fun `test equalsAsDecimals false`(valueA: String?, valueB: String?) {
    assertFalse(MathHelper.equalsAsDecimals(valueA, valueB))
  }

  @ParameterizedTest
  @CsvSource(value = ["a,a", "1,a", "a,1"])
  fun `test equalsAsDecimals throws`(valueA: String, valueB: String) {
    val ex: Exception = assertThrows { MathHelper.equalsAsDecimals(valueA, valueB) }
    assertInstanceOf(NumberFormatException::class.java, ex)
    assertEquals(
      "Character a is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.",
      ex.message
    )
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "12.3456789,7,12.3456789",
        "12.34564,7,12.34564",
        "12.3456,7,12.3456",
        "10.0000000,7,10",
        "0.0000000,7,0",
        "10.005,2,10",
        "10.006,2,10.01",
        "1000000.5,7,1000000.5"
      ]
  )
  fun `test formatAmount keeps the requested scale without trailing zeros or grouping`(
    value: String,
    scale: Int,
    expected: String
  ) {
    assertEquals(expected, MathHelper.formatAmount(BigDecimal(value), scale))
  }

  @Test
  fun `test formatAmount ignores the JVM default locale`() {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(Locale("pt", "BR"))
      assertEquals("12.3456789", MathHelper.formatAmount(BigDecimal("12.3456789"), 7))
    } finally {
      Locale.setDefault(previous)
    }
  }

  @Test
  fun `test formatAmount has no overload without an explicit scale`() {
    val overloads = MathHelper::class.java.methods.filter { it.name == "formatAmount" }
    assertTrue(overloads.isNotEmpty())
    assertTrue(overloads.all { it.parameterCount == 2 })
  }
}
