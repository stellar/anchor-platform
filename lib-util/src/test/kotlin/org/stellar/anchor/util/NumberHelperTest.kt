package org.stellar.anchor.util

import org.junit.jupiter.api.Assertions.assertFalse
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
        "1.000, 2"
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
      ]
  )
  fun `test violating significant decimals`(value: String, maxDecimals: Int) {
    assert(!NumberHelper.hasProperSignificantDecimals(value, maxDecimals))
  }
}
