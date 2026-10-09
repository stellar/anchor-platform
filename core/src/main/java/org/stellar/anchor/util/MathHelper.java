package org.stellar.anchor.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Arrays;
import org.stellar.anchor.api.asset.AssetInfo;

public class MathHelper {
  // Stored amounts are scaled, summed and formatted by this class. A value far outside anything a
  // valid amount can be (see NumberHelper.MAX_AMOUNT_MAGNITUDE for the request-side rule) would
  // expand to millions of digits, so those helpers refuse it. The bound is deliberately looser
  // than the request rule: computed values such as division results carry up to 34 digits.
  public static final int MAX_ARITHMETIC_DIGITS = 100;
  public static final int MAX_ARITHMETIC_LENGTH = 1000;
  // The widest valid value (100 integer and 100 fractional digits) has 200 digits. Reading the raw
  // precision is far cheaper than stripTrailingZeros(), which divides once per trailing zero.
  public static final int MAX_ARITHMETIC_PRECISION = 200;
  private static final String OUT_OF_RANGE = "amount exceeds the supported range";

  public static BigDecimal decimal(String value) {
    if (value == null) return null;
    return new BigDecimal(value);
  }

  public static BigDecimal decimal(String value, int scale) {
    if (value == null) return null;
    if (value.length() > MAX_ARITHMETIC_LENGTH) throw new ArithmeticException(OUT_OF_RANGE);
    BigDecimal decimal = new BigDecimal(value);
    checkArithmeticRange(decimal);
    return decimal.setScale(scale, RoundingMode.HALF_DOWN);
  }

  public static BigDecimal decimal(String value, AssetInfo asset) {
    if (value == null) return null;
    return decimal(value, asset.getSignificantDecimals());
  }

  public static BigDecimal decimal(Long value) {
    if (value == null) return null;
    return BigDecimal.valueOf(value);
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  public static boolean equalsAsDecimals(String valueA, String valueB) {
    if (valueA == null && valueB == null) {
      return true;
    } else if (valueA == null || valueB == null) {
      return false;
    }
    return decimal(valueA).compareTo(decimal(valueB)) == 0;
  }

  public static String formatAmount(BigDecimal amount, Integer decimals) {
    checkArithmeticRange(amount);
    BigDecimal newAmount = amount.setScale(decimals, RoundingMode.HALF_DOWN);

    DecimalFormat df = new DecimalFormat();
    df.setMaximumFractionDigits(decimals);
    df.setMinimumFractionDigits(0);
    df.setGroupingUsed(false);

    return df.format(newAmount);
  }

  public static String formatAmount(BigDecimal amount) {
    return formatAmount(amount, 4);
  }

  public static BigDecimal sum(AssetInfo assetInfo, String... values) {
    return Arrays.stream(values)
        .map(value -> decimal(value, assetInfo))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * Whether the value is small enough to be scaled, summed and formatted cheaply. A zero is checked
   * on its raw scale because stripTrailingZeros() collapses every zero to scale 0.
   */
  public static boolean isWithinArithmeticRange(BigDecimal value) {
    if (value.signum() == 0) {
      return Math.abs((long) value.scale()) <= MAX_ARITHMETIC_DIGITS;
    }
    // check the raw digit count first: a value padded with trailing zeros strips to a small number
    // but costs one division per zero to strip
    if (value.precision() > MAX_ARITHMETIC_PRECISION) {
      return false;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    long integerDigits = (long) stripped.precision() - (long) stripped.scale();
    return integerDigits <= MAX_ARITHMETIC_DIGITS && stripped.scale() <= MAX_ARITHMETIC_DIGITS;
  }

  private static void checkArithmeticRange(BigDecimal value) {
    if (!isWithinArithmeticRange(value)) throw new ArithmeticException(OUT_OF_RANGE);
  }
}
