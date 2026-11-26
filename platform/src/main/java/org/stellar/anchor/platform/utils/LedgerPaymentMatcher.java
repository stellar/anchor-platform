package org.stellar.anchor.platform.utils;

import static org.stellar.anchor.platform.utils.PaymentHelper.getLedgerPayment;
import static org.stellar.anchor.platform.utils.PaymentHelper.resolveSacAsset;

import java.util.List;
import org.stellar.anchor.api.exception.rpc.InvalidParamsException;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerInvokeHostFunctionOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation;
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPayment;
import org.stellar.anchor.platform.observer.stellar.SacToAssetMapper;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.xdr.Asset;
import org.stellar.sdk.xdr.Memo;
import org.stellar.sdk.xdr.MemoType;

/**
 * Decides whether a ledger transaction holds a payment for a SEP transaction: to the expected
 * account, with the expected memo and asset, for an amount above zero. The payment observer routes
 * on the same destination and memo before it calls the RPC, so this is the check a hash given
 * straight to the RPC must pass too.
 */
public final class LedgerPaymentMatcher {

  /**
   * What the SEP transaction expects to receive.
   *
   * @param account the receiving account
   * @param memo the stored memo, or null when none is stored and none is compared; it is compared
   *     by value, as the payment observer routes it, whatever the memo type
   * @param asset the asset as stored, for example {@code stellar:USDC:G...}
   * @param muxedIdFromAnySender whether a payment to a muxed address of {@code account} counts when
   *     its sender is not a contract
   */
  public record ExpectedPayment(
      String account, String memo, String asset, boolean muxedIdFromAnySender) {}

  private static final String[] STAGES = {"destination", "memo", "asset", "amount"};
  private static final int MATCHED = STAGES.length;

  private LedgerPaymentMatcher() {}

  /**
   * Returns when at least one payment of the ledger transaction satisfies every stage (destination,
   * memo, asset, amount). Otherwise throws, naming the furthest stage any payment reached, or the
   * destination when the transaction holds no payment at all.
   */
  public static void requirePayment(
      SacToAssetMapper sacToAssetMapper, LedgerTransaction ledgerTxn, ExpectedPayment expected)
      throws InvalidParamsException {
    int furthestFailedStage = 0;
    List<LedgerOperation> operations = ledgerTxn.getOperations();
    if (operations != null) {
      for (LedgerOperation operation : operations) {
        LedgerPayment payment = getLedgerPayment(operation);
        if (payment == null) {
          continue;
        }
        int failedStage = failedStage(sacToAssetMapper, ledgerTxn, payment, expected);
        if (failedStage == MATCHED) {
          return;
        }
        furthestFailedStage = Math.max(furthestFailedStage, failedStage);
      }
    }
    throw new InvalidParamsException(
        String.format(
            "Transaction (hash=%s) holds no payment with the expected %s (expected account=%s)",
            ledgerTxn.getHash(), STAGES[furthestFailedStage], expected.account()));
  }

  /** The index of the first stage the payment fails, or {@link #MATCHED}. */
  private static int failedStage(
      SacToAssetMapper sacToAssetMapper,
      LedgerTransaction ledgerTxn,
      LedgerPayment payment,
      ExpectedPayment expected) {
    Destination destination = matchDestination(payment, expected);
    if (destination == Destination.NONE) {
      return 0;
    }
    if (destination == Destination.ACCOUNT && !memoMatches(ledgerTxn.getMemo(), expected)) {
      return 1;
    }
    if (!assetMatches(sacToAssetMapper, payment, expected)) {
      return 2;
    }
    if (payment.getAmount() == null || payment.getAmount().signum() <= 0) {
      return 3;
    }
    return MATCHED;
  }

  /** How the payment's destination relates to the expected account. */
  private enum Destination {
    NONE,
    /** The destination is the expected account itself; the ledger memo still has to match. */
    ACCOUNT,
    /** A muxed address of the expected account, whose id stands in for the memo. */
    MUXED_ACCOUNT
  }

  private static Destination matchDestination(LedgerPayment payment, ExpectedPayment expected) {
    String to = payment.getTo();
    if (to == null || to.isEmpty()) {
      return Destination.NONE;
    }
    if (to.equals(expected.account())) {
      return Destination.ACCOUNT;
    }
    if (to.charAt(0) != 'M') {
      return Destination.NONE;
    }

    MuxedAccount muxed;
    try {
      muxed = new MuxedAccount(to);
    } catch (RuntimeException ex) {
      return Destination.NONE;
    }
    if (!muxed.getAccountId().equals(expected.account())) {
      return Destination.NONE;
    }
    if (!expected.muxedIdFromAnySender() && !isFromContract(payment)) {
      return Destination.NONE;
    }
    if (expected.memo() == null) {
      return Destination.MUXED_ACCOUNT;
    }
    boolean idIsTheStoredMemo =
        muxed.getMuxedId() != null && muxed.getMuxedId().toString().equals(expected.memo());
    return idIsTheStoredMemo ? Destination.MUXED_ACCOUNT : Destination.NONE;
  }

  private static boolean isFromContract(LedgerPayment payment) {
    String from = payment.getFrom();
    return from != null && !from.isEmpty() && from.charAt(0) == 'C';
  }

  private static boolean memoMatches(Memo ledgerMemo, ExpectedPayment expected) {
    if (expected.memo() == null) {
      return true;
    }
    if (ledgerMemo == null || ledgerMemo.getDiscriminant() == MemoType.MEMO_NONE) {
      return false;
    }
    // SEP-6 and SEP-24 transactions store a hash memo as hex, SEP-31 transactions as base64; the
    // observer looks each up in its own form, so either form of the ledger memo matches.
    return expected.memo().equals(MemoHelper.xdrMemoToString(ledgerMemo))
        || expected.memo().equals(hexForm(ledgerMemo));
  }

  private static String hexForm(Memo ledgerMemo) {
    try {
      return MemoHelper.memoAsString(org.stellar.sdk.Memo.fromXdr(ledgerMemo));
    } catch (Exception ex) {
      return null;
    }
  }

  private static boolean assetMatches(
      SacToAssetMapper sacToAssetMapper, LedgerPayment payment, ExpectedPayment expected) {
    Asset asset;
    if (payment instanceof LedgerInvokeHostFunctionOperation invoke) {
      asset = invoke.getAsset(false);
      if (asset == null) {
        asset = resolveSacAsset(sacToAssetMapper, invoke.getContractId());
      }
    } else {
      asset = payment.getAsset();
    }
    String name = AssetHelper.getSep11AssetName(asset);
    return name != null && ("stellar:" + name).equals(expected.asset());
  }
}
