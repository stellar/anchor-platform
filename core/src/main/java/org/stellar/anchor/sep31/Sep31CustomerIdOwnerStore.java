package org.stellar.anchor.sep31;

public interface Sep31CustomerIdOwnerStore {
  boolean verifyOrClaim(String customerId, String creatorAccount, String creatorMemo);
}
