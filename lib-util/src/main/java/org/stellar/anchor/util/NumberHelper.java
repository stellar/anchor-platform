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
      BigDecimal stripped = decimal.stripTrailingZeros();
      // Reject numbers with extreme exponents (e.g., 1.0E+500000000) to prevent OOM
      // when the number is later expanded via toPlainString or setScale.
      int integerDigits = stripped.precision() - stripped.scale();
      if (integerDigits > 20 || stripped.scale() > 20) {
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
