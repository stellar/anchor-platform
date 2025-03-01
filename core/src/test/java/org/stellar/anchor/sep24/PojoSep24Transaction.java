package org.stellar.anchor.sep24;

import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.stellar.anchor.api.shared.FeeDescription;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.StellarTransaction;

@Data
public class PojoSep24Transaction implements Sep24Transaction {
  String id;
  String transactionId;
  String stellarTransactionId;
  String externalTransactionId;
  String status;
  String kind;
  Instant startedAt;
  Instant completedAt;
  Instant updatedAt;
  Instant userActionRequiredBy;
  String requestAssetCode;
  String requestAssetIssuer;
  String webAuthAccount;
  String webAuthAccountMemo;
  String withdrawAnchorAccount;
  String fromAccount;
  String toAccount;
  String memoType;
  String memo;
  String protocol;
  String clientDomain;
  String clientName;
  Boolean claimableBalanceSupported;
  String amountIn;
  String amountOut;
  String amountFee;
  String amountInAsset;
  String amountOutAsset;
  String amountFeeAsset;
  Boolean refunded;
  Sep24Refunds refunds;
  String amountExpected;
  List<StellarTransaction> stellarTransactions;
  String message;
  String refundMemo;
  String refundMemoType;
  String quoteId;
  String sourceAsset;
  String destinationAsset;
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
}
