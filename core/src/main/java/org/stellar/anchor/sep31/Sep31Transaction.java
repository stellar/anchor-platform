package org.stellar.anchor.sep31;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.stellar.anchor.SepTransaction;
import org.stellar.anchor.api.sep.operation.Sep31Operation;
import org.stellar.anchor.api.sep.sep31.Sep31GetTransactionResponse;
import org.stellar.anchor.api.shared.*;

@SuppressWarnings("unused")
public interface Sep31Transaction extends SepTransaction {
  Long getStatusEta();

  void setStatusEta(Long statusEta);

  String getAmountExpected();

  void setAmountExpected(String amountExpected);

  String getAmountIn();

  void setAmountIn(String amountIn);

  String getAmountInAsset();

  void setAmountInAsset(String amountInAsset);

  String getAmountOut();

  void setAmountOut(String amountOut);

  String getAmountOutAsset();

  void setAmountOutAsset(String amountOutAsset);

  @Deprecated // ANCHOR-636
  String getAmountFee();

  @Deprecated // ANCHOR-636
  void setAmountFee(String amountFee);

  @Deprecated // ANCHOR-636
  String getAmountFeeAsset();

  @Deprecated // ANCHOR-636
  void setAmountFeeAsset(String amountFeeAsset);

  FeeDetails getFeeDetails();

  void setFeeDetails(FeeDetails feeDetails);

  String getStellarAccountId();

  void setStellarAccountId(String stellarAccountId);

  String getStellarMemo();

  void setStellarMemo(String stellarMemo);

  String getStellarMemoType();

  void setStellarMemoType(String stellarMemoType);

  Instant getTransferReceivedAt();

  void setTransferReceivedAt(Instant transferReceivedAt);

  void setStellarTransactions(List<StellarTransaction> stellarTransactions);

  String getExternalTransactionId();

  void setExternalTransactionId(String externalTransactionId);

  Boolean getRefunded();

  void setRefunded(Boolean refunded);

  Sep31Refunds getRefunds();

  void setRefunds(Sep31Refunds sep31Refunds);

  String getRequiredInfoMessage();

  void setRequiredInfoMessage(String requiredInfoMessage);

  Map<String, String> getFields();

  void setFields(Map<String, String> fields);

  Sep31Operation.Fields getRequiredInfoUpdates();

  void setRequiredInfoUpdates(Sep31Operation.Fields requiredInfoUpdates);

  String getQuoteId();

  void setQuoteId(String quoteId);

  String getClientDomain();

  void setClientDomain(String clientDomain);

  String getClientName();

  void setClientName(String clientName);

  void setSenderId(String sourceId);

  String getSenderId();

  void setReceiverId(String destinationId);

  String getReceiverId();

  StellarId getCreator();

  void setCreator(StellarId creator);

  default Customers getCustomers() {
    return new Customers(
        StellarId.builder().id(getSenderId()).build(),
        StellarId.builder().id(getReceiverId()).build());
  }

  /**
   * Create a Sep31GetTransactionResponse object out of this SEP-31 Transaction object.
   *
   * @return a Sep31GetTransactionResponse object.
   */
  default Sep31GetTransactionResponse toSep31GetTransactionResponse() {
    Sep31GetTransactionResponse.Refunds refunds = null;
    if (getRefunds() != null) {
      refunds = getRefunds().toSep31TransactionResponseRefunds();
    }

    return Sep31GetTransactionResponse.builder()
        .transaction(
            Sep31GetTransactionResponse.TransactionResponse.builder()
                .id(getId())
                .status(getStatus())
                .statusEta(getStatusEta())
                .amountIn(getAmountIn())
                .amountInAsset(getAmountInAsset())
                .amountOut(getAmountOut())
                .amountOutAsset(getAmountOutAsset())
                .amountFee(getAmountFee())
                .amountFeeAsset(getAmountFeeAsset())
                .feeDetails(getFeeDetails())
                .quoteId(getQuoteId())
                .stellarAccountId(getStellarAccountId())
                .stellarMemo(getStellarMemo())
                .stellarMemoType(getStellarMemoType())
                .startedAt(getStartedAt())
                .completedAt(getCompletedAt())
                .userActionRequiredBy(getUserActionRequiredBy())
                .stellarTransactionId(getStellarTransactionId())
                .externalTransactionId(getExternalTransactionId())
                .refunded(getRefunded())
                .refunds(refunds)
                .requiredInfoMessage(getRequiredInfoMessage())
                .requiredInfoUpdates(getRequiredInfoUpdates())
                .build())
        .build();
  }
}
