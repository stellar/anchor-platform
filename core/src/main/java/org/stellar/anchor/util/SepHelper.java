package org.stellar.anchor.util;

import static org.stellar.anchor.api.sep.SepTransactionStatus.*;
import static org.stellar.anchor.util.MathHelper.decimal;

import java.util.Set;
import java.util.UUID;
import org.stellar.anchor.api.exception.InvalidStellarAccountException;
import org.stellar.anchor.api.sep.SepTransactionStatus;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.xdr.MemoType;

public class SepHelper {
  public enum AccountType {
    G,
    M,
    C,
  }

  public static AccountType accountType(String account) {
    return switch (account.charAt(0)) {
      case 'G' -> AccountType.G;
      case 'M' -> AccountType.M;
      case 'C' -> AccountType.C;
      default ->
          throw new IllegalArgumentException(
              String.format("Invalid account type for account: %s", account));
    };
  }

  /**
   * Generates an Id for SEP transactions.
   *
   * @return An Id in UUID format
   */
  public static String generateSepTransactionId() {
    return UUID.randomUUID().toString();
  }

  public static String memoTypeString(MemoType memoType) {
    String result =
        switch (memoType) {
          case MEMO_ID -> "id";
          case MEMO_HASH -> "hash";
          case MEMO_TEXT -> "text";
          case MEMO_NONE -> "none";
          case MEMO_RETURN -> "return";
        };

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
