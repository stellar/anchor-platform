package org.stellar.anchor.util;

import static org.stellar.anchor.api.sep.SepTransactionStatus.*;
import static org.stellar.anchor.util.MathHelper.decimal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.api.exception.BadRequestException;
import org.stellar.anchor.api.exception.InvalidStellarAccountException;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.sdk.AccountConverter;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.xdr.*;

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
   * @param strAccount
   * @return If the account is in the format of 1) G..., returns null 2) G...:memo, returns the memo
   *     3) M..., returns null
   */
  public static String getAccountMemo(String strAccount) throws InvalidStellarAccountException {
    String[] tokens = strAccount.split(":");
    switch (tokens.length) {
      case 1:
        AccountConverter accountConverter;
        if (tokens[0].startsWith("G")) {
          accountConverter = AccountConverter.disableMuxed();
        } else {
          accountConverter = AccountConverter.enableMuxed();
        }
        // Check if the account is a valid G... or M...
        accountConverter.encode(tokens[0]);
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
    if (sAmount.signum() < 1) {
      throw new BadRequestException(messagePrefix + "amount should be positive");
    }
    return sAmount;
  }

  public static void validateAmountLimit(String messagePrefix, String amount, Long min, Long max)
      throws AnchorException {
    BigDecimal sAmount = validateAmount("", amount);
    BigDecimal bdMin = new BigDecimal(min);
    BigDecimal bdMax = new BigDecimal(max);
    if (sAmount.compareTo(bdMin) < 0) {
      throw new BadRequestException(String.format("%samount less than min limit", messagePrefix));
    }
    if (sAmount.compareTo(bdMax) > 0) {
      throw new BadRequestException(String.format("%samount exceeds max limit", messagePrefix));
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

  static final List<SepTransactionStatus> sep24Statuses =
      List.of(
          INCOMPLETE,
          PENDING_USR_TRANSFER_START,
          PENDING_USR_TRANSFER_COMPLETE,
          PENDING_EXTERNAL,
          PENDING_ANCHOR,
          PENDING_STELLAR,
          PENDING_TRUST,
          PENDING_USER,
          COMPLETED,
          NO_MARKET,
          TOO_SMALL,
          TOO_LARGE,
          ERROR);

  static final List<SepTransactionStatus> sep31Statuses =
      List.of(
          PENDING_SENDER,
          PENDING_STELLAR,
          PENDING_CUSTOMER_INFO_UPDATE,
          PENDING_TRANSACTION_INFO_UPDATE,
          PENDING_RECEIVER,
          PENDING_EXTERNAL,
          COMPLETED,
          EXPIRED,
          ERROR);
}
