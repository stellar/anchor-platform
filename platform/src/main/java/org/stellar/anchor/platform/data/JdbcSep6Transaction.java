package org.stellar.anchor.platform.data;

import com.google.gson.annotations.SerializedName;
import com.vladmihalcea.hibernate.type.json.JsonType;
import java.util.List;
import java.util.Map;
import javax.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.springframework.beans.BeanUtils;
import org.stellar.anchor.api.shared.InstructionField;
import org.stellar.anchor.api.shared.Refunds;
import org.stellar.anchor.sep6.Sep6Transaction;

@Getter
@Setter
@Entity
@Access(AccessType.FIELD)
@Table(name = "sep6_transaction")
@TypeDef(name = "json", typeClass = JsonType.class)
@NoArgsConstructor
public class JdbcSep6Transaction extends JdbcSepTransaction implements Sep6Transaction {
  public String getProtocol() {
    return "6";
  }

  @SerializedName("status_eta")
  @Column(name = "status_eta")
  Long statusEta;

  @SerializedName("kind")
  @Column(name = "kind")
  String kind;

  @SerializedName("transaction_id")
  @Column(name = "transaction_id")
  String transactionId;

  @SerializedName("type")
  @Column(name = "type")
  String type;

  @SerializedName("request_asset_code")
  @Column(name = "request_asset_code")
  String requestAssetCode;

  @SerializedName("request_asset_issuer")
  @Column(name = "request_asset_issuer")
  String requestAssetIssuer;

  @SerializedName("amount_expected")
  @Column(name = "amount_expected")
  String amountExpected;

  @SerializedName("sep10_account")
  @Column(name = "sep10_account")
  String sep10Account;

  @SerializedName("sep10_account_memo")
  @Column(name = "sep10_account_memo")
  String sep10AccountMemo;

  @SerializedName("withdraw_anchor_account")
  @Column(name = "withdraw_anchor_account")
  String withdrawAnchorAccount;

  @SerializedName("from_account")
  @Column(name = "from_account")
  String fromAccount;

  @SerializedName("to_account")
  @Column(name = "to_account")
  String toAccount;

  @SerializedName("memo")
  @Column(name = "memo")
  String memo;

  @SerializedName("memo_type")
  @Column(name = "memo_type")
  String memoType;

  @SerializedName("client_domain")
  @Column(name = "client_domain")
  String clientDomain;

  @SerializedName("client_name")
  @Column(name = "client_name")
  String clientName;

  @SerializedName("quote_id")
  @Column(name = "quote_id")
  String quoteId;

  @SerializedName("message")
  @Column(name = "message")
  String message;

  @Column(columnDefinition = "json")
  @Type(type = "json")
  Refunds refunds;

  @Override
  public Refunds getRefunds() {
    return refunds;
  }

  @Override
  public void setRefunds(Refunds refunds) {
    if (refunds != null) {
      this.refunds = new Refunds();
      BeanUtils.copyProperties(refunds, this.refunds);
    }
  }

  @SerializedName("refund_memo")
  @Column(name = "refund_memo")
  String refundMemo;

  @SerializedName("refund_memo_type")
  @Column(name = "refund_memo_type")
  String refundMemoType;

  @SerializedName("required_info_message")
  @Column(name = "required_info_message")
  String requiredInfoMessage;

  @SerializedName("required_info_updates")
  @Column(name = "required_info_updates")
  @Type(type = "json")
  List<String> requiredInfoUpdates;

  @SerializedName("required_customer_info_message")
  @Column(name = "required_customer_info_message")
  String requiredCustomerInfoMessage;

  @SerializedName("required_customer_info_updates")
  @Column(name = "required_customer_info_updates")
  @Type(type = "json")
  List<String> requiredCustomerInfoUpdates;

  @Column(name = "instructions")
  @Type(type = "json")
  Map<String, InstructionField> instructions;
}
