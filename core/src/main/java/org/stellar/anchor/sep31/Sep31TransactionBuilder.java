package org.stellar.anchor.sep31;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.stellar.anchor.api.sep.operation.Sep31Operation;
import org.stellar.anchor.api.shared.FeeDetails;
import org.stellar.anchor.api.shared.StellarId;
import org.stellar.anchor.api.shared.StellarTransaction;

public class Sep31TransactionBuilder {
  final Sep31Transaction txn;

  public Sep31TransactionBuilder(Sep31TransactionStore factory) {
    this.txn = factory.newTransaction();
  }

  public Sep31TransactionBuilder id(String id) {
    txn.setId(id);
    return this;
  }

  public Sep31TransactionBuilder status(String status) {
    txn.setStatus(status);
    return this;
  }

  public Sep31TransactionBuilder statusEta(Long statusEta) {
    txn.setStatusEta(statusEta);
    return this;
  }

  public Sep31TransactionBuilder amountExpected(String amountExpected) {
    txn.setAmountExpected(amountExpected);
    return this;
  }

  public Sep31TransactionBuilder amountIn(String amountIn) {
    txn.setAmountIn(amountIn);
    return this;
  }

  public Sep31TransactionBuilder amountInAsset(String amountInAsset) {
    txn.setAmountInAsset(amountInAsset);
    return this;
  }

  public Sep31TransactionBuilder amountOut(String amountOut) {
    txn.setAmountOut(amountOut);
    return this;
  }

  public Sep31TransactionBuilder amountOutAsset(String amountOutAsset) {
    txn.setAmountOutAsset(amountOutAsset);
    return this;
  }

  public Sep31TransactionBuilder feeDetails(FeeDetails feeDetails) {
    txn.setFeeDetails(feeDetails);

    return this;
  }

  public Sep31TransactionBuilder stellarAccountId(String stellarAccountId) {
    txn.setStellarAccountId(stellarAccountId);
    return this;
  }

  public Sep31TransactionBuilder stellarMemo(String stellarMemo) {
    txn.setStellarMemo(stellarMemo);
    return this;
  }

  public Sep31TransactionBuilder stellarMemoType(String stellarMemoType) {
    txn.setStellarMemoType(stellarMemoType);
    return this;
  }

  public Sep31TransactionBuilder updatedAt(Instant updatedAt) {
    txn.setUpdatedAt(updatedAt);
    return this;
  }

  public Sep31TransactionBuilder transferReceivedAt(Instant transferReceivedAt) {
    txn.setTransferReceivedAt(transferReceivedAt);
    return this;
  }

  public Sep31TransactionBuilder startedAt(Instant startedAt) {
    txn.setStartedAt(startedAt);
    return this;
  }

  public Sep31TransactionBuilder completedAt(Instant completedAt) {
    txn.setCompletedAt(completedAt);
    return this;
  }

  public Sep31TransactionBuilder stellarTransactionId(String stellarTransactionId) {
    txn.setStellarTransactionId(stellarTransactionId);
    return this;
  }

  public Sep31TransactionBuilder stellarTransactions(List<StellarTransaction> stellarTransactions) {
    txn.setStellarTransactions(stellarTransactions);
    return this;
  }

  public Sep31TransactionBuilder externalTransactionId(String externalTransactionId) {
    txn.setExternalTransactionId(externalTransactionId);
    return this;
  }

  public Sep31TransactionBuilder refunded(Boolean refunded) {
    txn.setRefunded(refunded);
    return this;
  }

  public Sep31TransactionBuilder refunds(Sep31Refunds sep31Refunds) {
    txn.setRefunds(sep31Refunds);
    return this;
  }

  public Sep31TransactionBuilder requiredInfoMessage(String requiredInfoMessage) {
    txn.setRequiredInfoMessage(requiredInfoMessage);
    return this;
  }

  public Sep31TransactionBuilder fields(Map<String, String> fields) {
    txn.setFields(fields);
    return this;
  }

  public Sep31TransactionBuilder requiredInfoUpdates(Sep31Operation.Fields requiredInfoUpdates) {
    txn.setRequiredInfoUpdates(requiredInfoUpdates);
    return this;
  }

  public Sep31TransactionBuilder quoteId(String quoteId) {
    txn.setQuoteId(quoteId);
    return this;
  }

  public Sep31TransactionBuilder clientDomain(String clientDomain) {
    txn.setClientDomain(clientDomain);
    return this;
  }

  public Sep31TransactionBuilder clientName(String clientName) {
    txn.setClientName(clientName);
    return this;
  }

  public Sep31TransactionBuilder senderId(String senderId) {
    txn.setSenderId(senderId);
    return this;
  }

  public Sep31TransactionBuilder receiverId(String receiverId) {
    txn.setReceiverId(receiverId);
    return this;
  }

  public Sep31TransactionBuilder creator(StellarId creator) {
    txn.setCreator(creator);
    return this;
  }

  public Sep31Transaction build() {
    return txn;
  }
}
