package org.stellar.anchor.platform.data;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.stellar.anchor.api.shared.FeeDescription;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.StellarTransaction;
import org.stellar.anchor.util.GsonUtils;

@Getter
@Setter
@MappedSuperclass
public abstract class JdbcSepTransaction {
  @Transient static Gson gson = GsonUtils.getInstance();

  @Id String id;

  String status;

  @SerializedName("updated_at")
  @Column(name = "updated_at")
  Instant updatedAt;

  @SerializedName("amount_in")
  @Column(name = "amount_in")
  String amountIn;

  @SerializedName("amount_in_asset")
  @Column(name = "amount_in_asset")
  String amountInAsset;

  @SerializedName("amount_out")
  @Column(name = "amount_out")
  String amountOut;

  @SerializedName("amount_out_asset")
  @Column(name = "amount_out_asset")
  String amountOutAsset;

  @SerializedName("amount_fee")
  @Column(name = "amount_fee")
  String amountFee;

  @SerializedName("amount_fee_asset")
  @Column(name = "amount_fee_asset")
  String amountFeeAsset;

  @SerializedName("fee_details")
  @Column(name = "fee_details")
  @Type(type = "json")
  List<FeeDescription> feeDetailsList;

  @SerializedName("started_at")
  @Column(name = "started_at")
  Instant startedAt;

  @SerializedName("completed_at")
  @Column(name = "completed_at")
  Instant completedAt;

  @SerializedName("transfer_received_at")
  @Column(name = "transfer_received_at")
  Instant transferReceivedAt;

  @SerializedName("user_action_required_by")
  @Column(name = "user_action_required_by")
  Instant userActionRequiredBy;

  @SerializedName("stellar_transaction_id")
  @Column(name = "stellar_transaction_id")
  String stellarTransactionId;

  @SerializedName("external_transaction_id")
  @Column(name = "external_transaction_id")
  String externalTransactionId;

  @Column(columnDefinition = "json")
  @Type(type = "json")
  List<StellarTransaction> stellarTransactions;

  public abstract String getProtocol();

  public void setFeeDetails(FeeDetails feeDetails) {
    setAmountFee(feeDetails.getTotal());
    setAmountFeeAsset(feeDetails.getAsset());
    setFeeDetailsList(feeDetails.getDetails());
  }

  public FeeDetails getFeeDetails() {
    if (getAmountFee() == null) {
      return null;
    }
    return new FeeDetails(getAmountFee(), getAmountFeeAsset(), getFeeDetailsList());
  }
}
