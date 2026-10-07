package org.stellar.anchor.util

import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.stellar.anchor.api.asset.AssetInfo

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

  private val rangeMessage = "amount exceeds the supported range"

  private fun asset7(): AssetInfo {
    val asset = mockk<AssetInfo>()
    every { asset.significantDecimals } returns 7
    return asset
  }

  private val hundredIntegerDigits = "1" + "0".repeat(99)
  private val hundredOneIntegerDigits = "1" + "0".repeat(100)
  private val scaleOneHundred = "0." + "0".repeat(99) + "1"
  private val scaleOneHundredOne = "0." + "0".repeat(100) + "1"

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "1e20000000",
        "1e-20000000",
        "0e-50000000",
        "0e+50000000",
        "-1e20000000",
        "1e101",
        "0e-101",
        "0e+101",
      ]
  )
  fun `test decimal with scale rejects out of range values`(value: String) {
    assertEquals(
      rangeMessage,
      assertThrows<ArithmeticException> { MathHelper.decimal(value, 7) }.message,
    )
  }

  @Test
  fun `test decimal with asset rejects out of range values`() {
    assertEquals(
      rangeMessage,
      assertThrows<ArithmeticException> { MathHelper.decimal("1e20000000", asset7()) }.message,
    )
  }

  @Test
  fun `test sum rejects an out of range value anywhere in the list`() {
    assertEquals(
      rangeMessage,
      assertThrows<ArithmeticException> { MathHelper.sum(asset7(), "1", "1e20000000", "2") }
        .message,
    )
  }

  @Test
  fun `test formatAmount rejects out of range values`() {
    assertEquals(
      rangeMessage,
      assertThrows<ArithmeticException> { MathHelper.formatAmount(BigDecimal("1e20000000"), 7) }
        .message,
    )
    assertEquals(
      rangeMessage,
      assertThrows<ArithmeticException> { MathHelper.formatAmount(BigDecimal("0e-50000000")) }
        .message,
    )
  }

  @Test
  fun `test values at the range boundary are accepted and 101 digits are rejected`() {
    assertEquals(
      BigDecimal(hundredIntegerDigits).setScale(7),
      MathHelper.decimal(hundredIntegerDigits, 7),
    )
    assertEquals(
      BigDecimal(scaleOneHundred).setScale(100),
      MathHelper.decimal(scaleOneHundred, 100),
    )
    assertEquals(BigDecimal("0").setScale(7), MathHelper.decimal("0e-100", 7))
    assertEquals(BigDecimal("0").setScale(7), MathHelper.decimal("0e+100", 7))
    assertThrows<ArithmeticException> { MathHelper.decimal(hundredOneIntegerDigits, 7) }
    assertThrows<ArithmeticException> { MathHelper.decimal(scaleOneHundredOne, 7) }
  }

  @Test
  fun `test the string length limit is 1000 characters`() {
    // strips to 1, so only the length can reject the second one
    val thousand = "1." + "0".repeat(998)
    val thousandOne = "1." + "0".repeat(999)
    assertEquals(1000, thousand.length)
    assertEquals(1001, thousandOne.length)
    assertEquals(BigDecimal("1.0000000"), MathHelper.decimal(thousand, 7))
    assertEquals(
      rangeMessage,
      assertThrows<ArithmeticException> { MathHelper.decimal(thousandOne, 7) }.message,
    )
  }

  @Test
  fun `test in range values return what they returned before`() {
    assertEquals(BigDecimal("1.5000000"), MathHelper.decimal("1.5", 7))
    assertEquals(BigDecimal("0.0000000"), MathHelper.decimal("0", 7))
    assertEquals(BigDecimal("500.0000000"), MathHelper.decimal("5e2", asset7()))
    assertEquals(BigDecimal("3.5000000"), MathHelper.sum(asset7(), "1", "2.5"))
    // integers only: DecimalFormat uses the default locale for the decimal separator
    assertEquals("15", MathHelper.formatAmount(BigDecimal("15"), 4))
    assertEquals("100", MathHelper.formatAmount(BigDecimal("1e2")))
    assertNull(MathHelper.decimal(null as String?, 7))
  }

  @Test
  fun `test decimal without a scale is unchanged`() {
    assertDoesNotThrow { MathHelper.decimal("1e20000000") }
    assertNull(MathHelper.decimal(null as String?))
  }

  @Test
  fun `test isWithinArithmeticRange`() {
    assertTrue(MathHelper.isWithinArithmeticRange(BigDecimal(hundredIntegerDigits)))
    assertFalse(MathHelper.isWithinArithmeticRange(BigDecimal(hundredOneIntegerDigits)))
    assertTrue(MathHelper.isWithinArithmeticRange(BigDecimal(scaleOneHundred)))
    assertFalse(MathHelper.isWithinArithmeticRange(BigDecimal(scaleOneHundredOne)))
    assertTrue(MathHelper.isWithinArithmeticRange(BigDecimal("0e-100")))
    assertFalse(MathHelper.isWithinArithmeticRange(BigDecimal("0e-101")))
    assertFalse(MathHelper.isWithinArithmeticRange(BigDecimal("0e+101")))
  }
}
