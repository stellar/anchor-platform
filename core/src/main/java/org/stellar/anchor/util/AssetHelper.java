package org.stellar.anchor.util;

import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.util.Currency;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.asset.DepositWithdrawInfo;
import org.stellar.anchor.api.asset.DepositWithdrawOperation;
import org.stellar.sdk.KeyPair;

public class AssetHelper {
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
    } catch (Exception ex) {
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
    return asset.split(":")[1];
  }

  /**
   * Returns the asset issuer from asset
   *
   * @param asset asset
   * @return The asset issuer. If issuer is absent, then returns NULL
   */
  public static String getAssetIssuer(String asset) {
    return asset.split(":").length >= 3 ? asset.split(":")[2] : null;
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
}
