package org.stellar.anchor.platform.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;

public interface JdbcSep6TransactionRepo
    extends CrudRepository<JdbcSep6Transaction, String>,
        PagingAndSortingRepository<JdbcSep6Transaction, String>,
        AllTransactionsRepository<JdbcSep6Transaction> {

  @NotNull
  Optional<JdbcSep6Transaction> findById(@NonNull String id);

  JdbcSep6Transaction findOneByTransactionId(String transactionId);

  JdbcSep6Transaction findOneByStellarTransactionId(String stellarTransactionId);

  JdbcSep6Transaction findOneByExternalTransactionId(String externalTransactionId);

  JdbcSep6Transaction findOneByWithdrawAnchorAccountAndMemoAndStatus(
      String withdrawAnchorAccount, String memo, String status);

  @Query(
      "SELECT t FROM JdbcSep6Transaction t WHERE t.webAuthAccount = :account"
          + " AND t.requestAssetCode = :assetCode"
          + " AND (:kind IS NULL OR t.kind = :kind)"
          + " AND t.startedAt > :noOlderThan"
          + " AND t.startedAt < :olderThan"
          + " ORDER BY t.startedAt DESC")
  List<JdbcSep6Transaction> findTransactionsWithFilters(
      @Param("account") String account,
      @Param("assetCode") String assetCode,
      @Param("kind") String kind,
      @Param("noOlderThan") Instant noOlderThan,
      @Param("olderThan") Instant olderThan,
      Pageable pageable);

  @Query(
      "SELECT t FROM JdbcSep6Transaction t WHERE t.webAuthAccount = :account"
          + " AND t.webAuthAccountMemo = :accountMemo"
          + " AND t.requestAssetCode = :assetCode"
          + " AND (:kind IS NULL OR t.kind = :kind)"
          + " AND t.startedAt > :noOlderThan"
          + " AND t.startedAt < :olderThan"
          + " ORDER BY t.startedAt DESC")
  List<JdbcSep6Transaction> findTransactionsWithMemoAndFilters(
      @Param("account") String account,
      @Param("accountMemo") String accountMemo,
      @Param("assetCode") String assetCode,
      @Param("kind") String kind,
      @Param("noOlderThan") Instant noOlderThan,
      @Param("olderThan") Instant olderThan,
      Pageable pageable);
}
