package org.stellar.anchor.util;

import static java.math.RoundingMode.HALF_UP;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.math.BigDecimal;
import java.util.Currency;
import lombok.SneakyThrows;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.asset.DepositWithdrawInfo;
import org.stellar.anchor.api.asset.DepositWithdrawOperation;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.xdr.Asset;

public class AssetHelper {
  public static BigDecimal XDR_SCALE_FACTOR = BigDecimal.valueOf(10000000);

  public static boolean isISO4217(String assetCode, String assetIssuer) {
    // assetIssuer must be empty to be a valid Fiat
    if (!isEmpty(assetIssuer)) {
      return false;
    }
    try {
      // check if assetCode is ISO4217 compliant
      Currency.getInstance(assetCode);
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  /**
   * Checks if the asset is a non-native issued asset.
   *
   * @param assetCode The asset code
   * @param assetIssuer The asset issuer
   * @return true if the asset is a non-native issued asset.
   */
  public static boolean isNonNativeAsset(String assetCode, String assetIssuer) {
    if (isEmpty(assetCode)) return false;
    if (isEmpty(assetIssuer)) return false;
    // check if assetIssuer is a valid wallet address
    try {
      KeyPair.fromAccountId(assetIssuer);
      return true;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  /**
   * Checks if the asset is native (XLM). Native assets have an empty assetIssuer.
   *
   * @param assetCode The asset code
   * @param assetIssuer The asset issuer
   * @return true if the asset is native (XLM)
   */
  public static boolean isNativeAsset(String assetCode, String assetIssuer) {
    return "native".equals(assetCode) && isEmpty(assetIssuer);
  }

  /**
   * Returns the asset code from asset
   *
   * @param asset asset
   * @return The asset code
   */
  public static String getAssetCode(String asset) {
    if (asset.equalsIgnoreCase("native")) {
      return "native";
    } else if (asset.startsWith("stellar:") || asset.startsWith("iso4217:")) {
      return asset.split(":")[1];
    } else {
      return asset.split(":")[0];
    }
  }

  /**
   * Returns the asset issuer from asset
   *
   * @param asset asset
   * @return The asset issuer. If issuer is absent, then returns NULL
   */
  public static String getAssetIssuer(String asset) {
    if (asset.equals("native")) {
      return null;
    } else if (asset.startsWith("stellar:")) {
      return asset.split(":")[2];
    } else if (asset.startsWith("iso4217:")) {
      return null; // fiat has no issuer
    } else {
      return asset.split(":")[1];
    }
  }

  /**
   * Returns the asset schema from asset id
   *
   * @param assetId asset id
   * @return The asset schema
   */
  public static String getAssetSchema(String assetId) {
    return assetId.split(":")[0];
  }

  /**
   * Returns the SEP-11 asset name for the given asset code and issuer.
   *
   * @param assetCode The asset code.
   * @param assetIssuer The asset issuer.
   * @return The SEP-11 asset name.
   */
  public static String getSep11AssetName(String assetCode, String assetIssuer) {
    if (assetCode.equals(AssetInfo.NATIVE_ASSET_CODE)) {
      return AssetInfo.NATIVE_ASSET_CODE;
    } else if (assetIssuer != null) {
      return assetCode + ":" + assetIssuer;
    } else {
      return assetCode;
    }
  }

  @SneakyThrows
  public static String getSep11AssetName(Asset xdrAsset) {
    if (xdrAsset == null) {
      return null;
    }
    return org.stellar.sdk.Asset.fromXdr(xdrAsset).toString();
  }

  // Check if deposit is enabled for the asset
  public static boolean isDepositEnabled(DepositWithdrawInfo info) {
    if (info == null || !info.getEnabled()) {
      return false;
    }
    DepositWithdrawOperation operation = info.getDeposit();
    return operation != null && operation.getEnabled();
  }

  // Check if withdrawal is enabled for the asset
  public static boolean isWithdrawEnabled(DepositWithdrawInfo info) {
    if (info == null || !info.getEnabled()) {
      return false;
    }
    DepositWithdrawOperation operation = info.getWithdraw();
    return operation != null && operation.getEnabled();
  }

  // Get AssetType String from XDR Asset object
  public static String getAssetType(Asset asset) {
    if (asset == null) {
      return null;
    }
    return switch (asset.getDiscriminant()) {
      case ASSET_TYPE_NATIVE -> "native";
      case ASSET_TYPE_CREDIT_ALPHANUM4 -> "credit_alphanum4";
      case ASSET_TYPE_CREDIT_ALPHANUM12 -> "credit_alphanum12";
      default ->
          throw new IllegalArgumentException("Unsupported asset type: " + asset.getDiscriminant());
    };
  }

  /**
   * Converts an amount in XDR format to a string representation.
   *
   * @param amount the amount in XDR format
   * @return the string representation of the amount
   */
  public static String fromXdrAmount(Long amount) {
    return BigDecimal.valueOf(amount).divide(XDR_SCALE_FACTOR, 7, HALF_UP).toPlainString();
  }

  /**
   * Converts a string representation of an amount to XDR format.
   *
   * @param amount the string representation of the amount
   * @return the amount in XDR format
   */
  public static Long toXdrAmount(String amount) {
    if (amount == null || amount.isEmpty()) {
      return null;
    }
    return new BigDecimal(amount).multiply(XDR_SCALE_FACTOR).setScale(0, HALF_UP).longValue();
  }
}
