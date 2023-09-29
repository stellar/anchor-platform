package org.stellar.anchor.sep6;

import java.math.BigDecimal;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stellar.anchor.api.exception.SepValidationException;
import org.stellar.anchor.api.sep.AssetInfo;
import org.stellar.anchor.asset.AssetService;
import org.stellar.sdk.KeyPair;

/** SEP-6 request validations */
@RequiredArgsConstructor
public class RequestValidator {
  @NonNull private final AssetService assetService;

  /**
   * Validates that the requested asset is valid and enabled for deposit.
   *
   * @param assetCode the requested asset code
   * @return the asset if its valid and enabled for deposit
   * @throws SepValidationException if the asset is invalid or not enabled for deposit
   */
  public AssetInfo getDepositAsset(String assetCode) throws SepValidationException {
    AssetInfo asset = assetService.getAsset(assetCode);
    if (asset == null || !asset.getSep6Enabled() || !asset.getDeposit().getEnabled()) {
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
  public AssetInfo getWithdrawAsset(String assetCode) throws SepValidationException {
    AssetInfo asset = assetService.getAsset(assetCode);
    if (asset == null || !asset.getSep6Enabled() || !asset.getWithdraw().getEnabled()) {
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
  public void validateAccount(String account) throws SepValidationException {
    try {
      KeyPair.fromAccountId(account);
    } catch (RuntimeException ex) {
      throw new SepValidationException(String.format("invalid account %s", account));
    }
  }
}
