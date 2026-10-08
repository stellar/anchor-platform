package org.stellar.anchor.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import org.stellar.anchor.api.asset.AssetInfo;

public class MathHelper {
  public static BigDecimal decimal(String value) {
    if (value == null) return null;
    return new BigDecimal(value);
  }

  public static BigDecimal decimal(String value, int scale) {
    if (value == null) return null;
    return new BigDecimal(value).setScale(scale, RoundingMode.HALF_DOWN);
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
    // toPlainString, unlike DecimalFormat, does not follow the JVM default locale: a comma
    // separator would make the persisted amount unreadable by new BigDecimal(String).
    return amount.setScale(decimals, RoundingMode.HALF_DOWN).stripTrailingZeros().toPlainString();
  }

  public static String formatAmount(BigDecimal amount) {
    return formatAmount(amount, 4);
  }

  public static BigDecimal sum(AssetInfo assetInfo, String... values) {
    return Arrays.stream(values)
        .map(value -> decimal(value, assetInfo))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
