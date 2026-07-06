package org.stellar.anchor.platform.data;

import java.util.Objects;
import org.stellar.anchor.sep31.Sep31CustomerIdOwnerStore;

public class JdbcSep31CustomerIdOwnerStore implements Sep31CustomerIdOwnerStore {
  private final JdbcSep31CustomerIdOwnerRepo repo;

  public JdbcSep31CustomerIdOwnerStore(JdbcSep31CustomerIdOwnerRepo repo) {
    this.repo = repo;
  }

  @Override
  public boolean verifyOrClaim(String customerId, String creatorAccount, String creatorMemo) {
    repo.claimIfAbsent(customerId, creatorAccount, creatorMemo);
    JdbcSep31CustomerIdOwner owner =
        repo.findById(customerId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "sep31_customer_id_owner row missing immediately after claim for id="
                            + customerId));

    return Objects.equals(owner.getCreatorAccount(), creatorAccount)
        && Objects.equals(owner.getCreatorMemo(), creatorMemo);
  }
}
