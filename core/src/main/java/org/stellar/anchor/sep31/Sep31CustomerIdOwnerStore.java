package org.stellar.anchor.sep31;

public interface Sep31CustomerIdOwnerStore {
  default boolean verifyOrClaim(String customerId, String creatorAccount, String creatorMemo) {
    return verifyOrClaim(customerId, creatorAccount, creatorMemo, false);
  }

  boolean verifyOrClaim(
      String customerId, String creatorAccount, String creatorMemo, boolean ignoreMemo);

  boolean isClaimed(String customerId);

  default boolean verify(String customerId, String creatorAccount, String creatorMemo) {
    return verify(customerId, creatorAccount, creatorMemo, false);
  }

  boolean verify(String customerId, String creatorAccount, String creatorMemo, boolean ignoreMemo);

  String getCreatorMemo(String customerId);

  boolean reconcileLegacyKey(
      String customerId,
      String legacyCreatorAccount,
      String legacyCreatorMemo,
      String newCreatorAccount,
      String newCreatorMemo);
}
