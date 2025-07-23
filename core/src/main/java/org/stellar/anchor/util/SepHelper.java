package org.stellar.anchor.util;

import static org.stellar.anchor.api.sep.SepTransactionStatus.*;
import static org.stellar.anchor.util.MathHelper.decimal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.InvalidStellarAccountException;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.xdr.MemoType;

public class SepHelper {
  /**
   * Generates an Id for SEP transactions.
   *
   * @return An Id in UUID format
   */
  public static String generateSepTransactionId() {
    return UUID.randomUUID().toString();
  }

  public static String memoTypeString(MemoType memoType) {
    String result = "";
    switch (memoType) {
      case MEMO_ID:
        result = "id";
        break;
      case MEMO_HASH:
        result = "hash";
        break;
      case MEMO_TEXT:
        result = "text";
        break;
      case MEMO_NONE:
        result = "none";
        break;
      case MEMO_RETURN:
        result = "return";
        break;
    }

    return result;
  }

  /**
   * Retrieves the memo of the account.
   *
   * @param strAccount The account in the format of G... or G...:memo
   * @return If the account is in the format of 1) G..., returns null 2) G...:memo, returns the memo
   *     3) M..., returns null
   * @throws InvalidStellarAccountException If the account is invalid
   */
  public static String getAccountMemo(String strAccount) throws InvalidStellarAccountException {
    String[] tokens = strAccount.split(":");
    switch (tokens.length) {
      // TODO: Should we add a catch here to throw an InvalidStellarAccountException exception in
      // case of invalid address?
      case 1:
        // Check if the account is a valid G... or M...
        new MuxedAccount(tokens[0]);
        return null;
      case 2:
        KeyPair.fromAccountId(tokens[0]);
        return tokens[1];
      default:
        throw new InvalidStellarAccountException(
            String.format("Invalid stellar account: %s", strAccount));
    }
  }

  public static boolean amountEquals(String amount1, String amount2) {
    return decimal(amount1).compareTo(decimal(amount2)) == 0;
  }

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
    switch (sep) {
      case 24:
        return (sep24Statuses.contains(status));
      case 31:
        return (sep31Statuses.contains(status));
      default:
        return false;
    }
  }

  public static final Set<SepTransactionStatus> sep6Statuses =
      Set.of(
          PENDING_EXTERNAL,
          PENDING_ANCHOR,
          ON_HOLD,
          PENDING_STELLAR,
          PENDING_TRUST,
          PENDING_USER,
          PENDING_USR_TRANSFER_START,
          PENDING_USR_TRANSFER_COMPLETE,
          PENDING_CUSTOMER_INFO_UPDATE,
          PENDING_TRANSACTION_INFO_UPDATE,
          COMPLETED,
          INCOMPLETE,
          EXPIRED,
          NO_MARKET,
          TOO_SMALL,
          TOO_LARGE,
          ERROR,
          REFUNDED);

  public static final Set<SepTransactionStatus> sep24Statuses =
      Set.of(
          PENDING_EXTERNAL,
          PENDING_ANCHOR,
          ON_HOLD,
          PENDING_STELLAR,
          PENDING_TRUST,
          PENDING_USER,
          PENDING_USR_TRANSFER_START,
          PENDING_USR_TRANSFER_COMPLETE,
          PENDING_TRANSACTION_INFO_UPDATE,
          COMPLETED,
          INCOMPLETE,
          EXPIRED,
          NO_MARKET,
          TOO_SMALL,
          TOO_LARGE,
          ERROR,
          REFUNDED);

  public static final Set<SepTransactionStatus> sep31Statuses =
      Set.of(
          PENDING_SENDER,
          PENDING_STELLAR,
          PENDING_CUSTOMER_INFO_UPDATE,
          PENDING_TRANSACTION_INFO_UPDATE,
          PENDING_RECEIVER,
          PENDING_EXTERNAL,
          COMPLETED,
          EXPIRED,
          ERROR,
          REFUNDED);
}
