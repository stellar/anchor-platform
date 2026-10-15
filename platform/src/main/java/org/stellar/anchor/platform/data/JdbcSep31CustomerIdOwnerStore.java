package org.stellar.anchor.platform.data;

import java.util.Objects;
import org.stellar.anchor.sep31.Sep31CustomerIdOwnerStore;
import org.stellar.anchor.util.Log;

public class JdbcSep31CustomerIdOwnerStore implements Sep31CustomerIdOwnerStore {
  private final JdbcSep31CustomerIdOwnerRepo repo;

  public JdbcSep31CustomerIdOwnerStore(JdbcSep31CustomerIdOwnerRepo repo) {
    this.repo = repo;
  }

  @Override
  public boolean verifyOrClaim(
      String customerId, String creatorAccount, String creatorMemo, boolean ignoreMemo) {
    if (repo.claimIfAbsent(customerId, creatorAccount, creatorMemo) == 1) {
      return true;
    }

    JdbcSep31CustomerIdOwner owner =
        repo.findById(customerId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "sep31_customer_id_owner row missing after a non-inserting claim for id="
                            + customerId));

    return Objects.equals(owner.getCreatorAccount(), creatorAccount)
        && (ignoreMemo || Objects.equals(owner.getCreatorMemo(), creatorMemo));
  }

  @Override
  public boolean isClaimed(String customerId) {
    return repo.findById(customerId).isPresent();
  }

  @Override
  public boolean verify(
      String customerId, String creatorAccount, String creatorMemo, boolean ignoreMemo) {
    return repo.findById(customerId)
        .map(
            owner ->
                Objects.equals(owner.getCreatorAccount(), creatorAccount)
                    && (ignoreMemo || Objects.equals(owner.getCreatorMemo(), creatorMemo)))
        .orElse(false);
  }

  @Override
  public String getCreatorMemo(String customerId) {
    return repo.findById(customerId).map(JdbcSep31CustomerIdOwner::getCreatorMemo).orElse(null);
  }

  @Override
  public boolean reconcileLegacyKey(
      String customerId,
      String legacyCreatorAccount,
      String legacyCreatorMemo,
      String newCreatorAccount,
      String newCreatorMemo) {
    if (repo.reassignIfCreatorAccountMatches(
            customerId, legacyCreatorAccount, legacyCreatorMemo, newCreatorAccount, newCreatorMemo)
        == 1) {
      logOwnerMoved(customerId, legacyCreatorAccount, newCreatorAccount);
    } else {
      reassignRowOfSameClient(customerId, legacyCreatorAccount, newCreatorAccount, newCreatorMemo);
    }
    return verify(customerId, newCreatorAccount, newCreatorMemo);
  }

  /**
   * Replaces a row keyed `<clientName>:<account>`, the format the V33 migration and the runtime
   * write, with the new key. The update is guarded by the key and memo read here, so a concurrent
   * change makes it match no row and the caller falls back to {@link #verify}.
   */
  private void reassignRowOfSameClient(
      String customerId, String clientName, String newCreatorAccount, String newCreatorMemo) {
    JdbcSep31CustomerIdOwner owner = repo.findById(customerId).orElse(null);
    if (owner == null
        || !isKeyOfClient(owner.getCreatorAccount(), clientName)
        || (Objects.equals(owner.getCreatorAccount(), newCreatorAccount)
            && Objects.equals(owner.getCreatorMemo(), newCreatorMemo))) {
      return;
    }
    if (repo.reassignIfCreatorAccountMatches(
            customerId,
            owner.getCreatorAccount(),
            owner.getCreatorMemo(),
            newCreatorAccount,
            newCreatorMemo)
        == 1) {
      logOwnerMoved(customerId, owner.getCreatorAccount(), newCreatorAccount);
    }
  }

  private static void logOwnerMoved(String customerId, String from, String to) {
    Log.infoF("Owner of customer id {} moved from {} to {}", customerId, from, to);
  }

  // A Stellar account never contains ':', so the client name is everything before the last one.
  private static boolean isKeyOfClient(String creatorAccount, String clientName) {
    if (creatorAccount == null || clientName == null) return false;
    int separator = creatorAccount.lastIndexOf(':');
    return separator > 0
        && separator < creatorAccount.length() - 1
        && creatorAccount.substring(0, separator).equals(clientName);
  }
}
