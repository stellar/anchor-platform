package org.stellar.anchor.sep6;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.stellar.anchor.SepTransaction;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.InstructionField;
import org.stellar.anchor.api.shared.Refunds;

public interface Sep6Transaction extends SepTransaction {

  /**
   * The database ID.
   *
   * @return The generated database ID.
   */
  String getId();

  void setId(String id);

  /**
   * Unique, anchor-generated ID for the transaction.
   *
   * @return The anchor-generated transaction ID.
   */
  String getTransactionId();

  void setTransactionId(String transactionId);

  /**
   * <code>transaction_id</code> on Stellar network of the transfer that either completed the
   * deposit or started the withdrawal.
   *
   * @return the Stellar transaction ID.
   */
  String getStellarTransactionId();

  void setStellarTransactionId(String stellarTransactionId);

  /**
   * ID of transaction on external network that either started the deposit or completed the
   * withdrawal.
   *
   * @return the external transaction ID.
   */
  String getExternalTransactionId();

  void setExternalTransactionId(String externalTransactionId);

  /**
   * Processing status of deposit/withdrawal.
   *
   * @return the transaction status.
   */
  String getStatus();

  void setStatus(String status);

  /**
   * Estimated number of seconds until a status change is expected.
   *
   * @return the status ETA.
   */
  Long getStatusEta();

  void setStatusEta(Long statusEta);

  /**
   * <code>deposit</code>, <code>deposit-exchange</code>, <code>withdrawal</code> or <code>
   * withdrawal-exchange</code>.
   *
   * @return the transaction kind.
   */
  String getKind();

  void setKind(String kind);

  /**
   * Start date and time of the transaction.
   *
   * @return the started at timestamp.
   */
  Instant getStartedAt();

  void setStartedAt(Instant startedAt);

  /**
   * The date and time of the transaction reaching <code>completed</code> or <code>refunded</code>
   * status.
   *
   * @return the completed at timestamp.
   */
  Instant getCompletedAt();

  void setCompletedAt(Instant completedAt);

  /**
   * The date and time the user funds were received.
   *
   * @return the transfer received at timestamp.
   */
  Instant getTransferReceivedAt();

  void setTransferReceivedAt(Instant transferReceivedAt);

  /**
   * The deposit or withdrawal method used. E.g. <code>bank_account</code>, <code>cash</code>
   *
   * @return the deposit or withdraw method.
   */
  String getType();

  void setType(String type);

  /**
   * The code of the asset of interest. E.g. BTC, ETH, USD, INR, etc.
   *
   * @return the request asset code.
   */
  String getRequestAssetCode();

  void setRequestAssetCode(String assetCode);

  /**
   * The issuer of the Stellar asset the user wants to receive for their deposit or want to withdraw
   * into an off-chain asset. If asset issuer is not provided, the anchor should use the asset
   * issued by themselves as described in their TOML file.
   *
   * @return the request asset issuer.
   */
  String getRequestAssetIssuer();

  void setRequestAssetIssuer(String assetIssuer);

  /**
   * Amount received by the anchor at the start of the tranaction as a string with up to 7 decimals.
   * It excludes any fees charged before the anchor received the funds.
   *
   * @return the amount received by the anchor.
   */
  String getAmountIn();

  void setAmountIn(String amountIn);

  /**
   * The asset code of the asset received by the anchor at the start of the tranaction.
   *
   * @return the asset code of the asset received by the anchor.
   */
  String getAmountInAsset();

  void setAmountInAsset(String amountInAsset);

  /**
   * Amount sent by the anchor at the end of the transaction as a string with up to 7 decimals. It
   * excludes the amount converted to XLM to fund the account and any external fees.
   *
   * @return the amount sent by the anchor.
   */
  String getAmountOut();

  void setAmountOut(String amountOut);

  /**
   * The asset code of the asset sent by the anchor at the end of the transaction.
   *
   * @return the asset code of the asset sent by the anchor.
   */
  String getAmountOutAsset();

  void setAmountOutAsset(String amountOutAsset);

  void setFeeDetails(FeeDetails feeDetails);

  /**
   * Description of fee charged by the anchor. Includes total break down of all fees that were
   * charged.
   *
   * @return Description of fee charged by the anchor.
   */
  FeeDetails getFeeDetails();

  /**
   * The amount requested by the user to deposit or withdraw.
   *
   * @return the amount expected by the user.
   */
  String getAmountExpected();

  void setAmountExpected(String amount);

  /**
   * The Stellar account used to authenticate using SEP-10 or SEP-45.
   *
   * @return the web auth account.
   */
  String getWebAuthAccount();

  void setWebAuthAccount(String webAuthAccount);

  /**
   * The Stellar account memo used to authenticate using SEP-10 or SEP-45.
   *
   * @return the web auth account memo.
   */
  String getWebAuthAccountMemo();

  void setWebAuthAccountMemo(String webAuthAccountMemo);

  /**
   * If this is a withdrawal, this is the anchor's Stellar account that the user transferred (or
   * will transfer) their issued asset to.
   *
   * @return the anchor's Stellar account.
   */
  String getWithdrawAnchorAccount();

  void setWithdrawAnchorAccount(String withdrawAnchorAccount);

  /**
   * For withdrawals, the Stellar account the assets were withdrawn from. For deposits, the
   * sent-from address (perhaps BTC, IBAN, bank account).
   *
   * @return the account the assets were deposited to or withdrawn from.
   */
  String getFromAccount();

  void setFromAccount(String fromAccount);

  /**
   * For withdrawals, the sent-to address (perhaps BTC, IBAN, bank account). For deposits, the
   * Stellar account the assets were deposited to.
   *
   * @return the account the assets were deposited to or withdrawn from.
   */
  String getToAccount();

  void setToAccount(String toAccount);

  /**
   * For withdrawals, this is the memo when the user transferred to withdrawAnchorAccount. It is
   * null if the user is not ready to receive payment, for example if KYC is not completed.
   *
   * <p>For deposits, this is the memo (if any) used to transfer the asset to the Stellar account.
   *
   * @return the withdrawal or deposit memo.
   */
  String getMemo();

  void setMemo(String memo);

  /**
   * The withdrawal or deposit memo type.
   *
   * @return the withdrawal or deposit memo type.
   */
  String getMemoType();

  void setMemoType(String memoType);

  /**
   * The client domain of the wallet that initiated the transaction.
   *
   * @return the client domain.
   */
  String getClientDomain();

  void setClientDomain(String clientDomain);

  /**
   * The name of the client that initiated the transaction.
   *
   * @return the client name.
   */
  String getClientName();

  void setClientName(String clientName);

  /**
   * The ID returned from a SEP-38 quote response. IF this is set, the user must deliver the deposit
   * funds to the anchor before the quote expires, otherwise the anchor may not honor the quote.
   *
   * @return the quote ID.
   */
  String getQuoteId();

  void setQuoteId(String quoteId);

  /**
   * Human-readable explanation of the transaction status.
   *
   * @return the message.
   */
  String getMessage();

  void setMessage(String message);

  /**
   * Describes any on or off-chain refund associated with this transaction.
   *
   * @return the refunds.
   */
  Refunds getRefunds();

  void setRefunds(Refunds refunds);

  /**
   * The memo the anchor must use when sending refund payments back to the user. If not specified,
   * the anchor should use the seame memo used by the user to send the original payment. If
   * specified, the <code>refundMemoType</code> must also be specified.
   *
   * @return the refund memo.
   */
  String getRefundMemo();

  void setRefundMemo(String refundMemo);

  /**
   * The refund memo type.
   *
   * @return the refund memo type.
   */
  String getRefundMemoType();

  void setRefundMemoType(String refundMemoType);

  /**
   * A human-readable message indicating any errors that require updated information from the user.
   *
   * @return the required info message.
   */
  String getRequiredInfoMessage();

  void setRequiredInfoMessage(String requiredInfoMessage);

  /**
   * A set of fields that require updates from the user.
   *
   * @return the required info updates.
   */
  List<String> getRequiredInfoUpdates();

  void setRequiredInfoUpdates(List<String> requiredInfoUpdates);

  /**
   * Describes how to complete the off-chain deposit.
   *
   * @return the deposit instructions.
   */
  Map<String, InstructionField> getInstructions();

  void setInstructions(Map<String, InstructionField> instructions);

  enum Kind {
    DEPOSIT("deposit"),
    WITHDRAWAL("withdrawal"),

    DEPOSIT_EXCHANGE("deposit-exchange"),
    WITHDRAWAL_EXCHANGE("withdrawal-exchange");

    private final String name;

    Kind(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }

    public boolean isDeposit() {
      return this.equals(DEPOSIT) || this.equals(DEPOSIT_EXCHANGE);
    }

    public boolean isWithdrawal() {
      return this.equals(WITHDRAWAL) || this.equals(WITHDRAWAL_EXCHANGE);
    }
  }
}
