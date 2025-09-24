package org.stellar.anchor.platform.utils;

import static java.util.stream.Collectors.toList;
import static org.stellar.anchor.util.MathHelper.decimal;

import java.math.BigDecimal;
import java.util.List;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import org.apache.commons.collections.CollectionUtils;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.rpc.method.AmountAssetRequest;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.api.shared.FeeDescription;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.asset.AssetService;
import org.stellar.anchor.platform.data.JdbcSepTransaction;
import org.stellar.anchor.util.MathHelper;
import org.stellar.anchor.util.SepHelper;
import org.stellar.anchor.util.StringHelper;

public class AssetValidationUtils {

  private static final String STELLAR_ASSET_PREFIX = "stellar:";

  public static boolean isStellarAsset(String asset) {
    return asset.startsWith(STELLAR_ASSET_PREFIX);
  }

  public static void validateFeeDetails(
      @NotNull FeeDetails fee,
      @Nullable JdbcSepTransaction jdbcTransaction,
      AssetService assetService)
      throws BadRequestException {
    validateAssetAmount(
        "fee_details", new AmountAssetRequest(fee.getTotal(), fee.getAsset()), true, assetService);

    if (jdbcTransaction != null
        && jdbcTransaction.getAmountFeeAsset() != null
        && !fee.getAsset().equals(jdbcTransaction.getAmountFeeAsset())) {
      throw new BadRequestException(
          "fee_details.asset is different from expected database asset ("
              + jdbcTransaction.getAmountFeeAsset()
              + ")");
    }

    if (fee.getDetails() != null) {
      BigDecimal sum =
          fee.getDetails().stream()
              .map(FeeDescription::getAmount)
              .map(MathHelper::decimal)
              .reduce(decimal(0L), BigDecimal::add);

      if (decimal(fee.getTotal()).compareTo(sum) != 0) {
        throw new BadRequestException(
            "fee_details.total is not equal to the sum of (fee_details.details.amount)");
      }
    }
  }

  /**
   * validateAsset will validate if the provided amount has valid values and if its asset is
   * supported.
   *
   * @see #validateAssetAmount(String, AmountAssetRequest, boolean, AssetService)
   */
  public static void validateAssetAmount(
      String fieldName, AmountAssetRequest amount, AssetService assetService)
      throws BadRequestException {
    validateAssetAmount(fieldName, amount, false, assetService);
  }

  /**
   * validateAssetAmount will validate if the provided amount has valid values and if its asset is
   * supported.
   *
   * @param fieldName is the name of the field that is being validated.
   * @param amount is the object containing the asset full name and the amount.
   * @param allowZero is a flag that indicates if the amount can be zero.
   * @param assetService is the service that will be used to check if the asset is supported.
   * @throws BadRequestException if the amount is invalid or the asset is not supported.
   */
  public static void validateAssetAmount(
      String fieldName, AmountAssetRequest amount, boolean allowZero, AssetService assetService)
      throws BadRequestException {
    if (amount == null) {
      return;
    }

    // asset amount needs to be non-empty and valid
    SepHelper.validateAmount(fieldName + ".", amount.getAmount(), allowZero);

    // asset name cannot be empty
    if (StringHelper.isEmpty(amount.getAsset())) {
      throw new BadRequestException(fieldName + ".asset cannot be empty");
    }

    List<AssetInfo> allAssets =
        assetService.listAllAssets().stream()
            .filter(assetInfo -> assetInfo.getSep38AssetName().equals(amount.getAsset()))
            .collect(toList());

    // asset name needs to be supported
    if (CollectionUtils.isEmpty(allAssets)) {
      throw new BadRequestException(
          String.format("'%s' is not a supported asset.", amount.getAsset()));
    }

    valiateAssetDecimals(allAssets, amount.getAmount());
  }

  public static void valiateAssetDecimals(List<AssetInfo> allAssets, String amount)
      throws BadRequestException {
    if (allAssets.size() == 1) {
      AssetInfo targetAsset = allAssets.get(0);

      if (targetAsset.getSignificantDecimals() != null) {
        // Check that significant decimal is correct
        if (decimal(amount, targetAsset).compareTo(decimal(amount)) != 0) {
          throw new BadRequestException(
              String.format(
                  "'%s' has invalid significant decimals. Expected: '%s'",
                  amount, targetAsset.getSignificantDecimals()));
        }
      }
    }
  }
}
