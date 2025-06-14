package org.stellar.anchor.sep6;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.InstructionField;
import org.stellar.anchor.api.shared.Refunds;

public class Sep6TransactionBuilder {
  final Sep6Transaction txn;

  public Sep6TransactionBuilder(Sep6TransactionStore factory) {
    txn = factory.newInstance();
  }

  public Sep6TransactionBuilder id(String id) {
    txn.setId(id);
    return this;
  }

  public Sep6TransactionBuilder transactionId(String txnId) {
    txn.setTransactionId(txnId);
    return this;
  }

  public Sep6TransactionBuilder stellarTransactionId(String txnId) {
    txn.setStellarTransactionId(txnId);
    return this;
  }

  public Sep6TransactionBuilder externalTransactionId(String txnId) {
    txn.setExternalTransactionId(txnId);
    return this;
  }

  public Sep6TransactionBuilder status(String status) {
    txn.setStatus(status);
    return this;
  }

  public Sep6TransactionBuilder statusEta(Long statusEta) {
    txn.setStatusEta(statusEta);
    return this;
  }

  public Sep6TransactionBuilder kind(String kind) {
    txn.setKind(kind);
    return this;
  }

  public Sep6TransactionBuilder startedAt(Instant time) {
    txn.setStartedAt(time);
    return this;
  }

  public Sep6TransactionBuilder userActionRequiredBy(Instant time) {
    txn.setUserActionRequiredBy(time);
    return this;
  }

  public Sep6TransactionBuilder completedAt(Instant time) {
    txn.setCompletedAt(time);
    return this;
  }

  public Sep6TransactionBuilder type(String type) {
    txn.setType(type);
    return this;
  }

  public Sep6TransactionBuilder assetCode(String assetCode) {
    txn.setRequestAssetCode(assetCode);
    return this;
  }

  public Sep6TransactionBuilder assetIssuer(String assetIssuer) {
    txn.setRequestAssetIssuer(assetIssuer);
    return this;
  }

  public Sep6TransactionBuilder amountIn(String amountIn) {
    txn.setAmountIn(amountIn);
    return this;
  }

  public Sep6TransactionBuilder amountInAsset(String amountInAsset) {
    txn.setAmountInAsset(amountInAsset);
    return this;
  }

  public Sep6TransactionBuilder amountOut(String amountOut) {
    txn.setAmountOut(amountOut);
    return this;
  }

  public Sep6TransactionBuilder amountOutAsset(String amountOutAsset) {
    txn.setAmountOutAsset(amountOutAsset);
    return this;
  }

  public Sep6TransactionBuilder feeDetails(FeeDetails feeDetails) {
    txn.setFeeDetails(feeDetails);
    return this;
  }

  public Sep6TransactionBuilder amountExpected(String amountExpected) {
    txn.setAmountExpected(amountExpected);
    return this;
  }

  public Sep6TransactionBuilder webAuthAccount(String webAuthAccount) {
    txn.setWebAuthAccount(webAuthAccount);
    return this;
  }

  public Sep6TransactionBuilder webAuthAccountMemo(String webAuthAccount) {
    txn.setWebAuthAccountMemo(webAuthAccount);
    return this;
  }

  public Sep6TransactionBuilder withdrawAnchorAccount(String withdrawAnchorAccount) {
    txn.setWithdrawAnchorAccount(withdrawAnchorAccount);
    return this;
  }

  public Sep6TransactionBuilder fromAccount(String fromAccount) {
    txn.setFromAccount(fromAccount);
    return this;
  }

  public Sep6TransactionBuilder toAccount(String toAccount) {
    txn.setToAccount(toAccount);
    return this;
  }

  public Sep6TransactionBuilder memo(String memo) {
    txn.setMemo(memo);
    return this;
  }

  public Sep6TransactionBuilder memoType(String memoType) {
    txn.setMemoType(memoType);
    return this;
  }

  public Sep6TransactionBuilder clientDomain(String clientDomain) {
    txn.setClientDomain(clientDomain);
    return this;
  }

  public Sep6TransactionBuilder clientName(String clientName) {
    txn.setClientName(clientName);
    return this;
  }

  public Sep6TransactionBuilder quoteId(String quoteId) {
    txn.setQuoteId(quoteId);
    return this;
  }

  public Sep6TransactionBuilder message(String message) {
    txn.setMessage(message);
    return this;
  }

  public Sep6TransactionBuilder refunds(Refunds refunds) {
    txn.setRefunds(refunds);
    return this;
  }

  public Sep6TransactionBuilder refundMemo(String refundMemo) {
    txn.setRefundMemo(refundMemo);
    return this;
  }

  public Sep6TransactionBuilder refundMemoType(String refundMemoType) {
    txn.setRefundMemoType(refundMemoType);
    return this;
  }

  public Sep6TransactionBuilder requiredInfoMessage(String requiredInfoMessage) {
    txn.setRequiredInfoMessage(requiredInfoMessage);
    return this;
  }

  public Sep6TransactionBuilder requiredInfoUpdates(List<String> requiredInfoUpdates) {
    txn.setRequiredInfoUpdates(requiredInfoUpdates);
    return this;
  }

  public Sep6TransactionBuilder instructions(Map<String, InstructionField> instructions) {
    txn.setInstructions(instructions);
    return this;
  }

  public Sep6Transaction build() {
    return txn;
  }
}
