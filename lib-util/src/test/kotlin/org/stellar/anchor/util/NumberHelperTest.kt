package org.stellar.anchor.util

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class NumberHelperTest {
  @ParameterizedTest
  @ValueSource(strings = ["1", "100", "100000", "100.000"])
  fun `test positive numbers`(value: String) {
    assert(NumberHelper.isPositiveNumber(value))
  }

  @ParameterizedTest
  @ValueSource(strings = ["0", "-1", "-100", "-100000", "-100.000"])
  @NullSource
  fun `test non positive numbers`(value: String?) {
    assertFalse(NumberHelper.isPositiveNumber(value))
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "1, 2",
        "-1, 2",
        "1.00, 2",
        "100.00, 2",
        "101.00, 2",
        "100.000000, 2",
        "101.000000, 2",
        "-1.00, 2",
        "01.00, 2",
        "1.000, 2",
        "1.0E+5, 2",
        "1.0E+10, 2",
        "1.23E+2, 2",
        "9999999999.99, 2",
        "0, 2",
        "0.00, 2",
        "0.00000000000000000000, 20",
      ]
  )
  fun `test proper significant decimals`(value: String, maxDecimals: Int) {
    assert(NumberHelper.hasProperSignificantDecimals(value, maxDecimals))
  }

  @ParameterizedTest
  @CsvSource(
    value =
      [
        "1.001, 2",
        "-1.001, 2",
        "1.0000001, 4",
        "-1.0000001, 4",
        "a, 1, 2",
        "1.0E+500000000, 2",
        "1.0E+20, 2",
        "9.99E+999999999, 4",
        "1E+21, 2",
        "1.0E-21, 4",
        "-1.0E+500000000, 2",
      ]
  )
  fun `test violating significant decimals`(value: String, maxDecimals: Int) {
    assert(!NumberHelper.hasProperSignificantDecimals(value, maxDecimals))
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "0e-50000000",
        "0e+50000000",
        "0.000000000000000000000",
        "-0e-50000000",
        "0e-21",
        "0e+21",
      ]
  )
  fun `test zero with an extreme scale is not a reasonable magnitude`(value: String) {
    assertFalse(NumberHelper.hasReasonableMagnitude(BigDecimal(value)))
    assertFalse(NumberHelper.hasProperSignificantDecimals(value, 7))
  }

  @ParameterizedTest
  @ValueSource(strings = ["0", "0.00", "0.00000000000000000000", "0e+5", "0e-20", "0e+20"])
  fun `test zero with a bounded scale is a reasonable magnitude`(value: String) {
    assertTrue(NumberHelper.hasReasonableMagnitude(BigDecimal(value)))
  }
}
