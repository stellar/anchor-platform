package org.stellar.anchor.util;

import static org.stellar.anchor.util.AssetHelper.isDepositEnabled;
import static org.stellar.anchor.util.AssetHelper.isWithdrawEnabled;
import static org.stellar.anchor.util.MathHelper.decimal;

import java.math.BigDecimal;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stellar.anchor.api.asset.StellarAssetInfo;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.anchor.asset.AssetService;
import org.stellar.sdk.Address;
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.scval.Scv;

/** SEP request validations */
@RequiredArgsConstructor
public class SepRequestValidator {
  @NonNull private final AssetService assetService;

  public static void validateAmount(String amount) throws AnchorException {
    validateAmount("", amount);
  }

  public static BigDecimal validateAmount(String messagePrefix, String amount)
      throws AnchorException {
    return validateAmount(messagePrefix, amount, false);
  }

  public static BigDecimal validateAmount(String messagePrefix, String amount, boolean allowZero)
      throws BadRequestException {
    // assetName
    if (StringHelper.isEmpty(amount)) {
      throw new BadRequestException(messagePrefix + "amount cannot be empty");
    }

    BigDecimal sAmount;
    try {
      sAmount = decimal(amount);
    } catch (NumberFormatException e) {
      throw new BadRequestException(messagePrefix + "amount is invalid", e);
    }

    if (allowZero) {
      if (sAmount.signum() < 0) {
        throw new BadRequestException(messagePrefix + "amount should be non-negative");
      }
    } else {
      if (sAmount.signum() < 1) {
        throw new BadRequestException(messagePrefix + "amount should be positive");
      }
    }
    return sAmount;
  }

  public static void validateAmountLimit(String messagePrefix, String amount, Long min, Long max)
      throws AnchorException {
    BigDecimal sAmount = validateAmount("", amount);

    // Validate min amount
    if (min != null) {
      BigDecimal bdMin = new BigDecimal(min);
      if (sAmount.compareTo(bdMin) < 0) {
        throw new BadRequestException(String.format("%samount less than min limit", messagePrefix));
      }
    }

    // Validate max amount
    if (max != null) {
      BigDecimal bdMax = new BigDecimal(max);
      if (sAmount.compareTo(bdMax) > 0) {
        throw new BadRequestException(String.format("%samount exceeds max limit", messagePrefix));
      }
    }
  }

  /**
   * Validates whether a specified funding method is supported for a given asset.
   *
   * @param assetID the unique id of the asset being validated.
   * @param method the funding method to validate.
   * @param supportedMethods a list of funding methods supported for the asset.
   * @throws BadRequestException if the provided funding method is not in the list of supported
   *     methods.
   */
  public static void validateFundingMethod(
      String assetID, String method, List<String> supportedMethods) throws BadRequestException {
    if (StringHelper.isEmpty(method)) {
      throw new BadRequestException("funding_method cannot be empty");
    }
    if (!supportedMethods.contains(method)) {
      throw new BadRequestException(
          String.format(
              "invalid funding method %s for asset %s, supported types are %s",
              method, assetID, supportedMethods));
    }
  }

  /**
   * Checks if the status is valid in a SEP.
   *
   * @param sep The sep number.
   * @param status The name (String) of the status to be checked.
   * @return true, if valid. Otherwise false
   */
  public static boolean validateTransactionStatus(String status, int sep) {
    for (SepTransactionStatus transactionStatus : SepTransactionStatus.values()) {
      if (transactionStatus.getStatus().equals(status)) {
        return validateTransactionStatus(transactionStatus, sep);
      }
    }

    return false;
  }

  /**
   * Checks if the status is valid in a SEP.
   *
   * @param sep The sep number.
   * @param status The status to be checked.
   * @return true, if valid. Otherwise false
   */
  public static boolean validateTransactionStatus(SepTransactionStatus status, int sep) {
    return switch (sep) {
      case 6 -> (SepHelper.sep6Statuses.contains(status));
      case 24 -> (SepHelper.sep24Statuses.contains(status));
      case 31 -> (SepHelper.sep31Statuses.contains(status));
      default -> false;
    };
  }

  /**
   * Validates that the requested asset is valid and enabled for deposit.
   *
   * @param assetCode the requested asset code
   * @return the asset if its valid and enabled for deposit
   * @throws SepValidationException if the asset is invalid or not enabled for deposit
   */
  public StellarAssetInfo getDepositAsset(String assetCode) throws SepValidationException {
    StellarAssetInfo asset = (StellarAssetInfo) assetService.getAsset(assetCode);
    if (asset == null || !isDepositEnabled(asset.getSep6())) {
      throw new SepValidationException(String.format("invalid operation for asset %s", assetCode));
    }
    return asset;
  }

  /**
   * Validates that the requested asset is valid and enabled for withdrawal.
   *
   * @param assetCode the requested asset code
   * @return the asset if its valid and enabled for withdrawal
   * @throws SepValidationException if the asset is invalid or not enabled for withdrawal
   */
  public StellarAssetInfo getWithdrawAsset(String assetCode) throws SepValidationException {
    StellarAssetInfo asset = (StellarAssetInfo) assetService.getAsset(assetCode);
    if (asset == null || !isWithdrawEnabled(asset.getSep6())) {
      throw new SepValidationException(String.format("invalid operation for asset %s", assetCode));
    }
    return asset;
  }

  /**
   * Validates that the requested amount is within bounds.
   *
   * @param requestAmount the requested amount
   * @param assetCode the requested asset code
   * @param scale the scale of the asset
   * @param minAmount the minimum amount
   * @param maxAmount the maximum amount
   * @throws SepValidationException if the amount is not within bounds
   */
  public void validateAmount(
      String requestAmount, String assetCode, int scale, Long minAmount, Long maxAmount)
      throws SepValidationException {
    BigDecimal amount = new BigDecimal(requestAmount);
    if (amount.scale() > scale) {
      throw new SepValidationException(
          String.format(
              "invalid amount %s for asset %s, significant decimals is %s",
              requestAmount, assetCode, scale));
    }
    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new SepValidationException(
          String.format("invalid amount %s for asset %s", requestAmount, assetCode));
    }
    if (minAmount != null && amount.compareTo(BigDecimal.valueOf(minAmount)) < 0) {
      throw new SepValidationException(
          String.format("invalid amount %s for asset %s", requestAmount, assetCode));
    }
    if (maxAmount != null && amount.compareTo(BigDecimal.valueOf(maxAmount)) > 0) {
      throw new SepValidationException(
          String.format("invalid amount %s for asset %s", requestAmount, assetCode));
    }
  }

  /**
   * Validates that the requested deposit/withdrawal type is valid.
   *
   * @param requestType the requested type
   * @param assetCode the requested asset code
   * @param validTypes the valid types
   * @throws SepValidationException if the type is invalid
   */
  public void validateTypes(String requestType, String assetCode, List<String> validTypes)
      throws SepValidationException {
    if (StringHelper.isEmpty(requestType)) {
      throw new SepValidationException(
          String.format(
              "this field cannot be null or empty for asset %s, supported types are %s",
              assetCode, validTypes));
    }
    if (!validTypes.contains(requestType)) {
      throw new SepValidationException(
          String.format(
              "invalid type %s for asset %s, supported types are %s",
              requestType, assetCode, validTypes));
    }
  }

  /**
   * Validates that the account is a valid Stellar account.
   *
   * @param account the account
   * @throws SepValidationException if the account is invalid
   */
  public void validateAccount(String account) throws AnchorException {
    switch (account.charAt(0)) {
      case 'G':
      case 'C':
        try {
          Address.fromSCAddress(Scv.fromAddress(Scv.toAddress(account)).toSCAddress());
        } catch (RuntimeException ex) {
          throw new SepValidationException(String.format("invalid account %s", account));
        }
        break;
      case 'M':
        try {
          new MuxedAccount(account);
        } catch (RuntimeException ex) {
          throw new SepValidationException(String.format("invalid account %s", account));
        }
        break;
      default:
        throw new SepValidationException(String.format("invalid account %s", account));
    }
  }
}
