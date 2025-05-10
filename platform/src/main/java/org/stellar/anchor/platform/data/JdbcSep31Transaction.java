package org.stellar.anchor.platform.data;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import jakarta.persistence.*;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.beans.BeanUtils;
import org.stellar.anchor.SepTransaction;
import org.stellar.anchor.api.asset.Sep31Info;
import org.stellar.anchor.api.shared.StellarId;
import org.stellar.anchor.sep31.Sep31Refunds;
import org.stellar.anchor.sep31.Sep31Transaction;

@Getter
@Setter
@Entity
@Access(AccessType.FIELD)
@Table(name = "sep31_transaction")
@NoArgsConstructor
public class JdbcSep31Transaction extends JdbcSepTransaction
    implements Sep31Transaction, SepTransaction {
  public String getProtocol() {
    return "31";
  }

  @SerializedName("status_eta")
  @Column(name = "status_eta")
  Long statusEta;

  @SerializedName("from_account")
  @Column(name = "from_account")
  String fromAccount;

  @SerializedName("to_account")
  @Column(name = "to_account")
  String toAccount;

  @SerializedName("stellar_memo")
  @Column(name = "stellar_memo")
  String stellarMemo;

  @SerializedName("stellar_memo_type")
  @Column(name = "stellar_memo_type")
  String stellarMemoType;

  @SerializedName("quote_id")
  @Column(name = "quote_id")
  String quoteId;

  @SerializedName("client_domain")
  @Column(name = "client_domain")
  String clientDomain;

  @SerializedName("client_name")
  @Column(name = "client_name")
  String clientName;

  @SerializedName("sender_id")
  @Column(name = "sender_id")
  String senderId;

  @SerializedName("receiver_id")
  @Column(name = "receiver_id")
  String receiverId;

  @SerializedName("required_info_message")
  @Column(name = "required_info_message")
  String requiredInfoMessage;

  @Convert(converter = StellarIdConverter.class)
  @Column(length = 2047)
  StellarId creator;

  @SerializedName("funding_method")
  @Column(name = "funding_method")
  String fundingMethod;

  // Ignored by JPA and Gson
  @SerializedName("fields")
  @Transient
  Map<String, String> fields;

  @Access(AccessType.PROPERTY)
  @Column(name = "fields")
  public String getFieldsJson() {
    return gson.toJson(this.fields);
  }

  public void setFieldsJson(String fieldsJson) {
    if (fieldsJson != null) {
      this.fields = gson.fromJson(fieldsJson, new TypeToken<Map<String, String>>() {}.getType());
    }
  }

  // Ignored by JPA and Gson
  @SerializedName("required_info_updates")
  @Transient
  Sep31Info.Fields requiredInfoUpdates;

  @Access(AccessType.PROPERTY)
  @Column(name = "requiredInfoUpdates")
  public String getRequiredInfoUpdatesJson() {
    return gson.toJson(this.requiredInfoUpdates);
  }

  public void setRequiredInfoUpdatesJson(String requiredInfoUpdatesJson) {
    if (requiredInfoUpdatesJson != null) {
      this.requiredInfoUpdates = gson.fromJson(requiredInfoUpdatesJson, Sep31Info.Fields.class);
    }
  }

  Boolean refunded;

  @Column(columnDefinition = "json")
  @JdbcTypeCode(SqlTypes.JSON)
  JdbcSep31Refunds refunds;

  public Sep31Refunds getRefunds() {
    return refunds;
  }

  public void setRefunds(Sep31Refunds sep31Refunds) {
    if (sep31Refunds != null) {
      this.refunds = new JdbcSep31Refunds();
      BeanUtils.copyProperties(sep31Refunds, this.refunds);
    }
  }
}
