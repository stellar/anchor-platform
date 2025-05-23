package org.stellar.anchor.platform.data;

import com.google.gson.annotations.SerializedName;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.beans.BeanUtils;
import org.stellar.anchor.SepTransaction;
import org.stellar.anchor.sep24.Sep24Refunds;
import org.stellar.anchor.sep24.Sep24Transaction;

@Getter
@Setter
@Entity
@Access(AccessType.FIELD)
@Table(name = "sep24_transaction")
@NoArgsConstructor
public class JdbcSep24Transaction extends JdbcSepTransaction
    implements Sep24Transaction, SepTransaction {
  public String getProtocol() {
    return "24";
  }

  String kind;

  @SerializedName("status_eta")
  @Column(name = "status_eta")
  String statusEta;

  @SerializedName("transaction_id")
  @Column(name = "transaction_id")
  String transactionId;

  String message;

  Boolean refunded;

  @Column(columnDefinition = "json")
  @JdbcTypeCode(SqlTypes.JSON)
  JdbcSep24Refunds refunds;

  @Override
  public Sep24Refunds getRefunds() {
    return refunds;
  }

  @Override
  public void setRefunds(Sep24Refunds refunds) {
    if (refunds != null) {
      this.refunds = new JdbcSep24Refunds();
      BeanUtils.copyProperties(refunds, this.refunds);
    }
  }

  /**
   * If this is a withdrawal, this is the anchor's Stellar account that the user transferred (or
   * will transfer) their issued asset to.
   */
  @SerializedName("withdraw_anchor_account")
  @Column(name = "withdraw_anchor_account")
  String withdrawAnchorAccount;

  /** The memo for deposit or withdraw */
  String memo;

  /** The memo type of the transaction */
  @SerializedName("memo_type")
  @Column(name = "memo_type")
  String memoType;

  /**
   * Sent from address.
   *
   * <p>In a deposit transaction, this would be a non-stellar account such as, BTC, IBAN, or bank
   * account.
   *
   * <p>In a withdrawal transaction, this would be the stellar account the assets were withdrawn
   * from.
   */
  @SerializedName("from_account")
  @Column(name = "from_account")
  String fromAccount;

  /**
   * Sent to address.
   *
   * <p>In a deposit transaction, this would be a stellar account the assets were deposited to.
   *
   * <p>In a withdrawal transaction, this would be the non-stellar account such as BTC, IBAN, or
   * bank account.
   */
  @SerializedName("to_account")
  @Column(name = "to_account")
  String toAccount;

  @SerializedName("request_asset_code")
  @Column(name = "request_asset_code")
  String requestAssetCode;

  @SerializedName("request_asset_issuer")
  @Column(name = "request_asset_issuer")
  String requestAssetIssuer;

  /** The web auth account */
  @SerializedName("web_auth_account")
  @Column(name = "web_auth_account")
  String webAuthAccount;

  /** The web auth account memo */
  @SerializedName("web_auth_account_memo")
  @Column(name = "web_auth_account_memo")
  String webAuthAccountMemo;

  @SerializedName("client_domain")
  @Column(name = "client_domain")
  String clientDomain;

  @SerializedName("client_name")
  @Column(name = "client_name")
  String clientName;

  @SerializedName("claimable_balance_supported")
  @Column(name = "claimable_balance_supported")
  Boolean claimableBalanceSupported;

  @SerializedName("amount_expected")
  @Column(name = "amount_expected")
  String amountExpected;

  @SerializedName("refund_memo")
  @Column(name = "refund_memo")
  String refundMemo;

  @SerializedName("refund_memo_type")
  @Column(name = "refund_memo_type")
  String refundMemoType;

  @SerializedName("quote_id")
  @Column(name = "quote_id")
  String quoteId;
}
