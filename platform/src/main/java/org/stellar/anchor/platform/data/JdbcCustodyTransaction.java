package org.stellar.anchor.platform.data;

import com.google.gson.annotations.SerializedName;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@Entity
@Access(AccessType.FIELD)
@Table(name = "custody_transaction")
@NoArgsConstructor
@AllArgsConstructor
public class JdbcCustodyTransaction {

  @Id String id;

  @SerializedName("sep_tx_id")
  @Column(name = "sep_tx_id")
  String sepTxId;

  @SerializedName("external_tx_id")
  @Column(name = "external_tx_id")
  String externalTxId;

  @SerializedName("status")
  @Column(name = "status")
  String status;

  @SerializedName("amount")
  @Column(name = "amount")
  String amount;

  @SerializedName("asset")
  @Column(name = "asset")
  String asset;

  @SerializedName("created_at")
  @Column(name = "created_at")
  Instant createdAt;

  @SerializedName("updated_at")
  @Column(name = "updated_at")
  Instant updatedAt;

  @SerializedName("memo")
  @Column(name = "memo")
  String memo;

  @SerializedName("memo_type")
  @Column(name = "memo_type")
  String memoType;

  @SerializedName("protocol")
  @Column(name = "protocol")
  String protocol;

  @SerializedName("from_account")
  @Column(name = "from_account")
  String fromAccount;

  @SerializedName("to_account")
  @Column(name = "to_account")
  String toAccount;

  @SerializedName("kind")
  @Column(name = "kind")
  String kind;

  @SerializedName("reconciliation_attempt_count")
  @Column(name = "reconciliation_attempt_count")
  int reconciliationAttemptCount;

  @SerializedName("type")
  @Column(name = "type")
  String type;

  @SerializedName("amount_fee")
  @Column(name = "amount_fee")
  String amountFee;

  public enum PaymentType {
    @SerializedName("payment")
    PAYMENT("payment"),
    @SerializedName("refund")
    REFUND("refund");

    private final String type;

    PaymentType(String type) {
      this.type = type;
    }

    public String getType() {
      return type;
    }

    public static PaymentType from(String str) {
      for (PaymentType type : values()) {
        if (type.type.equals(str)) {
          return type;
        }
      }
      throw new IllegalArgumentException("No matching constant for [" + str + "]");
    }
  }
}
