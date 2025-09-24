package org.stellar.anchor.sep24;

import org.stellar.anchor.SepTransaction;
import org.stellar.anchor.api.shared.FeeDetails;

@SuppressWarnings("unused")
public interface Sep24Transaction extends SepTransaction {
  /**
   * Unique, anchor-generated id for the transaction.
   *
   * @return The <code>id</code> field of the SEP-24 transaction history.
   */
  String getTransactionId();

  void setTransactionId(String transactionId);

  /**
   * ID of transaction on external network that either started the deposit or completed the
   * withdrawal.
   *
   * @return The <code>external_transction_id</code> field of the SEP-24 transaction history.
   */
  String getExternalTransactionId();

  void setExternalTransactionId(String externalTransactionId);

  /**
   * <code>deposit</code> or <code>withdrawal</code> .
   *
   * @return The <code>kind</code> field of the SEP-24 transaction history.
   */
  String getKind();

  void setKind(String kind);

  /**
   * The code of the asset of interest. E.g. BTC, ETH, USD, INR, etc.
   *
   * @return <code>asset_code</code> field of the SEP-24 transaction history.
   */
  String getRequestAssetCode();

  void setRequestAssetCode(String assetCode);

  /**
   * The issuer of the stellar asset the user wants to receive for their deposit with the anchor. If
   * asset_issuer is not provided, the anchor should use the asset issued by themselves as described
   * in their TOML file.
   *
   * @return the asset issuer of the transaction's <code>asset_code</code> .
   */
  String getRequestAssetIssuer();

  void setRequestAssetIssuer(String assetIssuer);

  // Amount requested by the user as a string with up to 7 decimals.
  String getAmountExpected();

  void setAmountExpected(String amount);

  /**
   * The Stellar account used to authenticate SEP-10;.
   *
   * @return the stellar account.
   */
  String getSep10Account();

  void setSep10Account(String sep10Account);

  /**
   * The Stellar account memo used to authenticate SEP-10;.
   *
   * @return the stellar account memo
   */
  String getSep10AccountMemo();

  void setSep10AccountMemo(String sep10Account);

  /**
   * If this is a withdrawal, this is the anchor's Stellar account that the user transferred (or
   * will transfer) their issued asset to.
   *
   * @return <code>withdraw_anchor_account</code> field of the SEP-24 transaction history.
   */
  String getWithdrawAnchorAccount();

  void setWithdrawAnchorAccount(String withdrawAnchorAccount);

  /**
   * If withdrawal, the Stellar account the assets were withdrawn from.
   *
   * <p>If deposit, sent from address, perhaps BTC, IBAN, or bank account.
   *
   * @return <code>from</code> field of the SEP-24 transaction history.
   */
  String getFromAccount();

  void setFromAccount(String fromAccount);

  /**
   * If withdrawal, sent to address (perhaps BTC, IBAN, or bank account in the case of a withdrawal,
   * Stellar account in the case of a deposit).
   *
   * <p>If deposit, the Stellar account the deposited assets were sent to.
   *
   * @return <code>to</code> field of the SEP-24 transaction history.
   */
  String getToAccount();

  void setToAccount(String toAccount);

  /**
   * If withdrawal, this is the memo used when the user transferred to withdraw_anchor_account.
   * Assigned null if the withdraw is not ready to receive payment, for example if KYC is not
   * completed.
   *
   * <p>If deposit, this is the memo (if any) used to transfer the asset to the to Stellar address
   *
   * @return <code>withdraw_memo</code> or <code>deposit_memo</code> of the SEP-24 transaction
   *     history.
   */
  String getMemo();

  void setMemo(String memo);

  /**
   * Type for the <code>memo</code> field.
   *
   * @return <code>withdraw_memo_type</code> or <code>deposit_memo_type</code> of the SEP-24
   *     transaction history.
   */
  String getMemoType();

  void setMemoType(String memoType);

  /**
   * The client domain of the wallet that initiated the transaction.
   *
   * @return the client domain.
   */
  String getClientDomain();

  void setClientDomain(String domainClient);

  /**
   * The name of the client that initiated the transaction.
   *
   * @return the client name.
   */
  String getClientName();

  void setClientName(String nameClient);

  /**
   * True if the client supports receiving deposit transactions as a claimable balance, false
   * otherwise.
   *
   * @return <code>true</code> or <code>false</code>
   */
  Boolean getClaimableBalanceSupported();

  void setClaimableBalanceSupported(Boolean claimableBalanceSupported);

  /**
   * Amount received by anchor at start of transaction as a string with up to 7 decimals. Excludes
   * any fees charged before the anchor received the funds.
   *
   * @return <code>amount_in</code> field of the SEP-24 transaction history.
   */
  String getAmountIn();

  void setAmountIn(String amountIn);

  /**
   * The asset received or to be received by the Anchor. Must be present if the deposit/withdraw was
   * made using non-equivalent assets. The value must be in SEP-38 Asset Identification Format. See
   * the Asset Exchanges section for more information.
   *
   * @return <code>amount_in_asset</code> field of the SEP-24 transaction history.
   */
  String getAmountInAsset();

  void setAmountInAsset(String amountInAsset);

  /**
   * Amount sent by anchor to user at end of transaction as a string with up to 7 decimals. Excludes
   * amount converted to XLM to fund account and any external fees.
   *
   * @return <code>amount_out</code> field of the SEP-24 transaction history.
   */
  String getAmountOut();

  void setAmountOut(String amountOut);

  /**
   * The asset of the <code>amount_out</code> field.
   *
   * @return <code>amount_out_asset</code> field of the SEP-24 transaction history.
   */
  String getAmountOutAsset();

  void setAmountOutAsset(String amountOutAsset);

  /**
   * Amount of fee charged by anchor.
   *
   * @return <code>amount_fee</code> field of the SEP-24 transaction history.
   */
  @Deprecated // ANCHOR-636
  String getAmountFee();

  @Deprecated // ANCHOR-636
  void setAmountFee(String amountFee);

  /**
   * The asset in which fees are calculated in. Must be present if the deposit/withdraw was made
   * using non-equivalent assets. The value must be in SEP-38 Asset Identification Format. See the
   * Asset Exchanges section for more information.
   *
   * @return <code>amount_fee_asset</code> field of the SEP-24 transaction history.
   */
  @Deprecated // ANCHOR-636
  String getAmountFeeAsset();

  @Deprecated // ANCHOR-636
  void setAmountFeeAsset(String amountFeeAsset);

  /**
   * Description of fee charged by the anchor. The schema for this object is defined in the Fee
   * Details Object Schema and matches the one of SEP-38 quote's fee.
   *
   * @return Unified fields of SEP-24 transaction history complied from <code>amount_fee</code>,
   *     <code>amount_fee_asset</code> and <code>fee_details</code>
   */
  FeeDetails getFeeDetails();

  void setFeeDetails(FeeDetails feeDetails);

  Boolean getRefunded();

  void setRefunded(Boolean refunded);

  Sep24Refunds getRefunds();

  void setRefunds(Sep24Refunds refunds);

  /**
   * The memo the anchor must use when sending refund payments back to the user. If not specified,
   * the anchor should use the same memo used by the user to send the original payment. If
   * specified, <code>refund_memo_type</code> must also be specified.
   *
   * @return <code>refund_memo</code> provided in the SEP-24 withdrawal request.
   */
  String getRefundMemo();

  void setRefundMemo(String refundMemo);

  String getRefundMemoType();

  void setRefundMemoType(String refundMemoType);

  String getMessage();

  String getQuoteId();

  void setQuoteId(String quoteId);

  enum Kind {
    DEPOSIT("deposit"),
    WITHDRAWAL("withdrawal");

    private final String name;

    Kind(String name) {
      this.name = name;
    }

    public String toString() {
      return name;
    }
  }
}
