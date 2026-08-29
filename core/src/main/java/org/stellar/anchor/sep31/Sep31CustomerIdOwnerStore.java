package org.stellar.anchor.sep31;

public interface Sep31CustomerIdOwnerStore {
  boolean verifyOrClaim(String customerId, String creatorAccount, String creatorMemo);

  /**
   * Returns whether {@code customerId} already has an owner row, without claiming it (and without
   * {@code verifyOrClaim}'s side effect of claiming it on first reference). Lets a caller require a
   * stronger proof of ownership for an id with no owner yet -- e.g. {@code
   * Sep31Service#verifyCustomerOwnershipAndKyc} only calls {@code verifyOrClaim} for such an id
   * after independently verifying, via a SEP-12 identity lookup, that the authenticated caller is
   * this exact customer -- rather than letting {@code verifyOrClaim}'s claim-on-first-reference
   * semantics (still the intended behavior for {@code Sep12Service#putCustomer}'s own initial
   * registration) hand the id to whoever references it first.
   */
  boolean isClaimed(String customerId);
}
