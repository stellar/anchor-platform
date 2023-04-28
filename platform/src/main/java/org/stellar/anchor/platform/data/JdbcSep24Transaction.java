package org.stellar.anchor.platform.data;

import com.google.gson.annotations.SerializedName;
import com.vladmihalcea.hibernate.type.json.JsonType;
import javax.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.springframework.beans.BeanUtils;
import org.stellar.anchor.sep24.Sep24Refunds;
import org.stellar.anchor.sep24.Sep24Transaction;

@Getter
@Setter
@Entity
@Access(AccessType.FIELD)
@Table(name = "sep24_transaction")
@TypeDef(name = "json", typeClass = JsonType.class)
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

  @SerializedName("more_info_url")
  @Column(name = "more_info_url")
  String moreInfoUrl;

  @SerializedName("transaction_id")
  @Column(name = "transaction_id")
  String transactionId;

  String message;

  Boolean refunded;

  @Column(columnDefinition = "json")
  @Type(type = "json")
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

  /** The SEP10 account used for authentication. */
  @SerializedName("sep10_account")
  @Column(name = "sep10account")
  String sep10Account;

  /** The SEP10 account memo used for authentication. */
  @SerializedName("sep10_account_memo")
  @Column(name = "sep10account_memo")
  String sep10AccountMemo;

  @SerializedName("client_domain")
  @Column(name = "client_domain")
  String clientDomain;

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
}
