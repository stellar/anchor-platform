package org.stellar.anchor.util;

import static java.lang.Math.*;
import static java.math.RoundingMode.*;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class NumberHelper {
  public static RoundingMode DEFAULT_ROUNDING_MODE = FLOOR;
  // Stellar assets use 7 decimal places (stroops), and a long can hold up to 19 digits
  // (Long.MAX_VALUE = 9,223,372,036,854,775,807). 20 digits for both integer and fractional parts
  // is generous enough to represent any valid Stellar amount while preventing OOM from inputs with
  // extreme exponents that would exhaust memory when expanded.
  public static final int MAX_AMOUNT_MAGNITUDE = 20;

  public static boolean isPositiveNumber(String str) {
    if (str == null) {
      return false;
    }

    try {
      return new BigDecimal(str).compareTo(BigDecimal.ZERO) > 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  public static boolean isNonNegativeNumber(String str) {
    if (str == null) {
      return false;
    }

    try {
      return new BigDecimal(str).compareTo(BigDecimal.ZERO) >= 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  public static boolean hasReasonableMagnitude(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    long integerDigits = (long) stripped.precision() - (long) stripped.scale();
    return integerDigits <= MAX_AMOUNT_MAGNITUDE && stripped.scale() <= MAX_AMOUNT_MAGNITUDE;
  }

  public static boolean hasProperSignificantDecimals(String input, int maxDecimals) {
    try {
      BigDecimal decimal = new BigDecimal(input);
      BigDecimal stripped = decimal.stripTrailingZeros();
      if (!hasReasonableMagnitude(stripped)) {
        return false;
      }
      int scale = max(0, stripped.scale());

      return scale <= maxDecimals;
    } catch (NumberFormatException e) {
      // If the input is not a valid number, return false
      return false;
    }
  }
}
