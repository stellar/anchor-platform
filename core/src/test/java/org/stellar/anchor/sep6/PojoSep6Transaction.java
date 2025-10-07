package org.stellar.anchor.sep6;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.stellar.anchor.api.shared.*;

@Data
public class PojoSep6Transaction implements Sep6Transaction {
  String id;
  List<StellarTransaction> stellarTransactions;
  String transactionId;
  String stellarTransactionId;
  String externalTransactionId;
  String status;
  Long statusEta;
  String kind;
  Instant startedAt;
  Instant completedAt;
  Instant updatedAt;
  Instant userActionRequiredBy;
  Instant transferReceivedAt;
  String type;
  String requestAssetCode;
  String requestAssetIssuer;
  String amountIn;
  String amountInAsset;
  String amountOut;
  String amountOutAsset;
  String amountFee;
  String amountFeeAsset;
  String amountExpected;
  String webAuthAccount;
  String webAuthAccountMemo;
  String withdrawAnchorAccount;
  String fromAccount;
  String toAccount;
  String memo;
  String memoType;
  String clientDomain;
  String clientName;
  String quoteId;
  String message;
  Refunds refunds;
  String refundMemo;
  String refundMemoType;
  String requiredInfoMessage;
  String requestClientIpAddress;
  List<String> requiredInfoUpdates;
  Map<String, InstructionField> instructions;
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
