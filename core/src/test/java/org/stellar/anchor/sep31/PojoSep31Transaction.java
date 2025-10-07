package org.stellar.anchor.sep31;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.stellar.anchor.api.asset.Sep31Info;
import org.stellar.anchor.api.shared.FeeDescription;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.StellarId;
import org.stellar.anchor.api.shared.StellarTransaction;

@Data
public class PojoSep31Transaction implements Sep31Transaction {
  String id;
  String status;
  Long statusEta;
  String amountIn;
  String amountInAsset;
  String amountOut;
  String amountOutAsset;
  String amountFee;
  String amountFeeAsset;
  String fromAccount;
  String toAccount;
  String stellarMemo;
  String stellarMemoType;
  Instant startedAt;
  Instant completedAt;
  Instant userActionRequiredBy;
  String stellarTransactionId;
  List<StellarTransaction> stellarTransactions;
  String externalTransactionId;
  String requiredInfoMessage;
  String quoteId;
  String clientDomain;
  String clientName;
  Sep31Info.Fields requiredInfoUpdates;
  Map<String, String> fields;
  Boolean refunded;
  PojoSep31Refunds refunds;
  Instant updatedAt;
  Instant transferReceivedAt;
  String amountExpected;
  String receiverId;
  String senderId;
  StellarId creator;
  String fundingMethod;
  String requestClientIpAddress;
  List<FeeDescription> feeDetailsList;

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

  @Override
  public void setRefunds(Sep31Refunds sep31Refunds) {
    if (sep31Refunds == null) {
      this.refunds = null;
      return;
    }

    PojoSep31Refunds newRefunds = new PojoSep31Refunds();
    newRefunds.setAmountRefunded(sep31Refunds.getAmountRefunded());
    newRefunds.setAmountFee(sep31Refunds.getAmountFee());
    newRefunds.setRefundPayments(sep31Refunds.getRefundPayments());
    this.refunds = newRefunds;
  }
}
