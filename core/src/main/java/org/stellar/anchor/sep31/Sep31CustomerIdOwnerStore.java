package org.stellar.anchor.sep31;

public interface Sep31CustomerIdOwnerStore {
  boolean verifyOrClaim(String customerId, String creatorAccount, String creatorMemo);

  /**
   * Returns whether {@code customerId} already has an owner row, without claiming it. Used to
   * distinguish a pre-established ownership from a claim-on-first-reference happening as a side
   * effect of the current call (see ANCHOR-1248's "Known limitations": an id with no owner row is
   * handed to whoever references it first, which is an accepted risk for creating a transaction,
   * but should not additionally be trusted enough to disclose the referenced customer's SEP-12
   * status).
   */
  boolean isClaimed(String customerId);
}
