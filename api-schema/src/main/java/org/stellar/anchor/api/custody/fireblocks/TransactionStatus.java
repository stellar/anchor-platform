package org.stellar.anchor.api.custody.fireblocks;

import java.util.Set;

public enum TransactionStatus {
  SUBMITTED,
  QUEUED,
  PENDING_AUTHORIZATION,
  PENDING_SIGNATURE,
  BROADCASTING,
  PENDING_3RD_PARTY_MANUAL_APPROVAL,
  PENDING_3RD_PARTY,
  CONFIRMING,
  PARTIALLY_COMPLETED,
  PENDING_AML_SCREENING,
  COMPLETED,
  CANCELLED,
  REJECTED,
  BLOCKED,
  FAILED;

  public boolean isCompleted() {
    return Set.of(COMPLETED, CONFIRMING).contains(this);
  }

  public boolean isObservable() {
    return Set.of(FAILED, CANCELLED, BLOCKED, CONFIRMING, COMPLETED).contains(this);
  }

  // CONFIRMING webhook status means the transaction is complete on Stellar.
  // That's why events with COMPLETED status are ignored
  public boolean isObservableByWebhook() {
    return Set.of(FAILED, CANCELLED, BLOCKED, CONFIRMING).contains(this);
  }
}
