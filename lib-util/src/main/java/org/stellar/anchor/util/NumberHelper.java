package org.stellar.anchor.util;

import static java.lang.Math.*;
import static java.math.RoundingMode.*;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class NumberHelper {
  public static RoundingMode DEFAULT_ROUNDING_MODE = FLOOR;

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

  public static boolean hasProperSignificantDecimals(String input, int maxDecimals) {
    try {
      BigDecimal decimal = new BigDecimal(input);
      int scale = max(0, decimal.stripTrailingZeros().scale());

      return scale >= 0 && scale <= maxDecimals;
    } catch (NumberFormatException e) {
      // If the input is not a valid number, return false
      return false;
    }
  }
}
