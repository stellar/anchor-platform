package org.stellar.anchor.util;

import static java.lang.String.*;
import static org.stellar.anchor.api.asset.Sep31Info.*;
import static org.stellar.anchor.api.asset.Sep38Info.*;
import static org.stellar.anchor.util.ListHelper.isEmpty;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.stellar.anchor.api.asset.*;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.asset.AssetService;

@RequiredArgsConstructor
public class AssetValidator {
  private static final List<String> isoCountries = Arrays.asList(Locale.getISOCountries());

  public static void validate(AssetService assetService) throws InvalidConfigException {
    // Check for non-zero assets
    if (assetService == null || isEmpty(assetService.getAssets())) {
      throw new InvalidConfigException(
          "No assets defined in configuration. The 'items' array must contain at least one asset.");
    }

    List<String> errors = new ArrayList<>();

    // Check for duplicate assets
    Set<String> existingAssetNames = new HashSet<>();
    for (AssetInfo asset : assetService.getAssets()) {
      if (asset != null && !existingAssetNames.add(asset.getId())) {
        errors.add(
            format("Duplicate asset ID found: %s. Each asset ID must be unique.", asset.getId()));
      }
    }

    // Validate stellar assets
    for (StellarAssetInfo stellarAsset : assetService.getStellarAssets()) {
      try {
        validateStellarAsset(assetService, stellarAsset);
      } catch (InvalidConfigException e) {
        errors.add(e.getMessage());
      }
    }

    // Validate fiat assets
    for (FiatAssetInfo fiatAsset : assetService.getFiatAssets()) {
      try {
        validateFiatAsset(assetService, fiatAsset);
      } catch (InvalidConfigException e) {
        errors.add(e.getMessage());
      }
    }

    if (!errors.isEmpty()) {
      throw new InvalidConfigException(errors);
    }
  }

  static void validateStellarAsset(AssetService assetService, StellarAssetInfo stellarAssetInfo)
      throws InvalidConfigException {
    List<String> errors = new ArrayList<>();

    // Check for missing significant decimals field
    if (stellarAssetInfo.getSignificantDecimals() == null) {
      errors.add(
          format(
              "Asset %s: 'significant_decimals' is required for Stellar assets.",
              stellarAssetInfo.getId()));
    }

    // Check for missing distribution account
    if (StringHelper.isEmpty(stellarAssetInfo.getDistributionAccount())) {
      errors.add(
          format(
              "Asset %s: 'distribution_account' is required for Stellar assets.",
              stellarAssetInfo.getId()));
    }

    try {
      validateSep6(stellarAssetInfo.getSep6(), stellarAssetInfo.getId());
    } catch (InvalidConfigException e) {
      errors.add(e.getMessage());
    }

    try {
      validateSep24(stellarAssetInfo.getSep24(), stellarAssetInfo.getId());
    } catch (InvalidConfigException e) {
      errors.add(e.getMessage());
    }

    try {
      validateSep31(stellarAssetInfo.getSep31(), stellarAssetInfo.getId());
    } catch (InvalidConfigException e) {
      errors.add(e.getMessage());
    }

    try {
      validateSep38(assetService, stellarAssetInfo.getSep38(), stellarAssetInfo.getId());
    } catch (InvalidConfigException e) {
      errors.add(e.getMessage());
    }

    if (!errors.isEmpty()) {
      throw new InvalidConfigException(errors);
    }
  }

  static void validateFiatAsset(AssetService assetService, FiatAssetInfo fiatAssetInfo)
      throws InvalidConfigException {
    List<String> errors = new ArrayList<>();

    try {
      validateSep31(fiatAssetInfo.getSep31(), fiatAssetInfo.getId());
    } catch (InvalidConfigException e) {
      errors.add(e.getMessage());
    }

    try {
      validateSep38(assetService, fiatAssetInfo.getSep38(), fiatAssetInfo.getId());
    } catch (InvalidConfigException e) {
      errors.add(e.getMessage());
    }

    if (!errors.isEmpty()) {
      throw new InvalidConfigException(errors);
    }
  }

  static void validateSep6(Sep6Info sep6Info, String assetId) throws InvalidConfigException {
    // Validate SEP-6 fields
    try {
      validateDepositWithdrawInfo(sep6Info, assetId, "SEP-6");
    } catch (InvalidConfigException e) {
      throw new InvalidConfigException(e.getMessage());
    }
  }

  static void validateSep24(Sep24Info sep24Info, String assetId) throws InvalidConfigException {
    // Validate SEP-24 fields
    try {
      validateDepositWithdrawInfo(sep24Info, assetId, "SEP-24");
    } catch (InvalidConfigException e) {
      throw new InvalidConfigException(e.getMessage());
    }
  }

  static void validateSep31(Sep31Info sep31Info, String assetId) throws InvalidConfigException {
    if (sep31Info == null || !sep31Info.getEnabled()) return;

    List<String> errors = new ArrayList<>();

    // Validate quotes configuration
    boolean isQuotesSupported = sep31Info.isQuotesSupported();
    boolean isQuotesRequired = sep31Info.isQuotesRequired();
    if (isQuotesRequired && !isQuotesSupported) {
      errors.add(
          format(
              "Asset %s: SEP-31 'quotes_supported' must be true if 'quotes_required' is true.",
              assetId));
    }

    // Validate receive configuration
    ReceiveOperation receiveInfo = sep31Info.getReceive();
    if (receiveInfo != null) {
      if (receiveInfo.getMinAmount() != null && receiveInfo.getMinAmount() < 0) {
        errors.add(
            format(
                "Asset %s: SEP-31 receive 'min_amount' must be non-negative (current value: %s).",
                assetId, receiveInfo.getMinAmount()));
      }

      if (receiveInfo.getMaxAmount() != null && receiveInfo.getMaxAmount() <= 0) {
        errors.add(
            format(
                "Asset %s: SEP-31 receive 'max_amount' must be positive (current value: %s).",
                assetId, receiveInfo.getMaxAmount()));
      }

      // Check for empty and duplicate receive methods
      if (isEmpty(receiveInfo.getMethods())) {
        errors.add(format("Asset %s: SEP-31 requires at least one receive method.", assetId));
      } else {
        Set<String> existingReceiveMethods = new HashSet<>();
        for (String method : receiveInfo.getMethods()) {
          if (!existingReceiveMethods.add(method)) {
            errors.add(
                format("Asset %s: SEP-31 duplicate receive method found: %s", assetId, method));
          }
        }
      }
    }

    if (!errors.isEmpty()) {
      throw new InvalidConfigException(errors);
    }
  }

  static void validateSep38(AssetService assetService, Sep38Info sep38Info, String assetId)
      throws InvalidConfigException {
    if (sep38Info == null || !sep38Info.getEnabled()) return;

    List<String> errors = new ArrayList<>();

    // Validate exchangeable_assets
    if (!isEmpty(sep38Info.getExchangeableAssets())) {
      for (String exchangeableAsset : sep38Info.getExchangeableAssets()) {
        if (assetService.getAssetById(exchangeableAsset) == null) {
          errors.add(
              format(
                  "Asset %s: SEP-38 invalid exchangeable asset '%s'. The asset must be defined in the configuration.",
                  assetId, exchangeableAsset));
        }
      }
    }

    // Validate country codes
    if (sep38Info.getCountryCodes() != null) {
      for (String country : sep38Info.getCountryCodes()) {
        if (!isCountryCodeValid(country)) {
          errors.add(
              format(
                  "Asset %s: SEP-38 invalid country code '%s'. Must be a valid 2-letter ISO country code.",
                  assetId, country));
        }
      }
    }

    // Validate delivery methods
    if (sep38Info.getBuyDeliveryMethods() != null) {
      for (DeliveryMethod method : sep38Info.getBuyDeliveryMethods()) {
        if (StringHelper.isEmpty(method.getName())) {
          errors.add(format("Asset %s: SEP-38 buy delivery method name cannot be empty.", assetId));
        }
        if (StringHelper.isEmpty(method.getDescription())) {
          errors.add(
              format("Asset %s: SEP-38 buy delivery method description cannot be empty.", assetId));
        }
      }
    }

    if (sep38Info.getSellDeliveryMethods() != null) {
      // Validate methods
      for (DeliveryMethod method : sep38Info.getSellDeliveryMethods()) {
        if (StringHelper.isEmpty(method.getName())) {
          errors.add(
              format("Asset %s: SEP-38 sell delivery method name cannot be empty.", assetId));
        }
        if (StringHelper.isEmpty(method.getDescription())) {
          errors.add(
              format(
                  "Asset %s: SEP-38 sell delivery method description cannot be empty.", assetId));
        }
      }
    }

    if (!errors.isEmpty()) {
      throw new InvalidConfigException(errors);
    }
  }

  static boolean isCountryCodeValid(String countryCode) {
    return countryCode != null && countryCode.length() == 2 && isoCountries.contains(countryCode);
  }

  static void validateDepositWithdrawInfo(
      DepositWithdrawInfo dwInfo, String assetId, String sepType) throws InvalidConfigException {
    List<String> errors = new ArrayList<>();

    // Validate withdraw fields
    if (AssetHelper.isWithdrawEnabled(dwInfo)) {
      if (isEmpty(dwInfo.getWithdraw().getMethods())) {
        errors.add(
            format("Asset %s: %s requires at least one withdrawal method.", assetId, sepType));
      } else {
        Set<String> existingWithdrawTypes = new HashSet<>();
        for (String method : dwInfo.getWithdraw().getMethods()) {
          if (!existingWithdrawTypes.add(method)) {
            errors.add(
                format(
                    "Asset %s: %s duplicate withdraw method found: %s.", assetId, sepType, method));
          }
        }
      }

      if (dwInfo.getWithdraw().getMinAmount() != null && dwInfo.getWithdraw().getMinAmount() < 0) {
        errors.add(
            format(
                "Asset %s: %s withdraw 'min_amount' must be non-negative (current value: %s).",
                assetId, sepType, dwInfo.getWithdraw().getMinAmount()));
      }

      if (dwInfo.getWithdraw().getMaxAmount() != null && dwInfo.getWithdraw().getMaxAmount() <= 0) {
        errors.add(
            format(
                "Asset %s: %s withdraw 'max_amount' must be positive (current value: %s).",
                assetId, sepType, dwInfo.getWithdraw().getMaxAmount()));
      }
    }

    // Validate deposit fields
    if (AssetHelper.isDepositEnabled(dwInfo)) {
      if (isEmpty(dwInfo.getDeposit().getMethods())) {
        errors.add(format("Asset %s: %s requires at least one deposit method.", assetId, sepType));
      } else {
        Set<String> existingDepositTypes = new HashSet<>();
        for (String method : dwInfo.getDeposit().getMethods()) {
          if (!existingDepositTypes.add(method)) {
            errors.add(
                format(
                    "Asset %s: %s duplicate deposit method found: %s.", assetId, sepType, method));
          }
        }
      }
      if (dwInfo.getDeposit().getMinAmount() != null && dwInfo.getDeposit().getMinAmount() < 0) {
        throw new InvalidConfigException(
            format(
                "Asset %s: %s deposit 'min_amount' must be non-negative (current value: %s).",
                assetId, sepType, dwInfo.getDeposit().getMinAmount()));
      }
      if (dwInfo.getDeposit().getMaxAmount() != null && dwInfo.getDeposit().getMaxAmount() <= 0) {
        throw new InvalidConfigException(
            format(
                "Asset %s: %s deposit 'max_amount' must be positive (current value: %s).",
                assetId, sepType, dwInfo.getDeposit().getMaxAmount()));
      }
    }

    if (!errors.isEmpty()) {
      throw new InvalidConfigException(errors);
    }
  }
}
