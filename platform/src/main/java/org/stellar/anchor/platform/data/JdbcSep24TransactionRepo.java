package org.stellar.anchor.platform.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;
import org.stellar.anchor.sep24.Sep24Transaction;

public interface JdbcSep24TransactionRepo
    extends CrudRepository<JdbcSep24Transaction, String>,
        PagingAndSortingRepository<JdbcSep24Transaction, String>,
        AllTransactionsRepository<JdbcSep24Transaction> {
  Optional<JdbcSep24Transaction> findById(@NonNull String id);

  JdbcSep24Transaction findOneByTransactionId(String transactionId);

  JdbcSep24Transaction findOneByExternalTransactionId(String externalTransactionId);

  JdbcSep24Transaction findOneByStellarTransactionId(String stellarTransactionId);

  JdbcSep24Transaction findOneByWithdrawAnchorAccountAndMemoAndStatus(
      String withdrawAnchorAccount, String memo, String status);

  @Query(
      "SELECT t FROM JdbcSep24Transaction t WHERE t.webAuthAccount = :account"
          + " AND t.requestAssetCode = :assetCode"
          + " AND (:kind IS NULL OR t.kind = :kind)"
          + " AND t.startedAt > :noOlderThan"
          + " AND t.startedAt < :olderThan"
          + " ORDER BY t.startedAt DESC")
  List<JdbcSep24Transaction> findTransactionsWithFilters(
      @Param("account") String account,
      @Param("assetCode") String assetCode,
      @Param("kind") String kind,
      @Param("noOlderThan") Instant noOlderThan,
      @Param("olderThan") Instant olderThan,
      Pageable pageable);

  @Query(
      "SELECT t FROM JdbcSep24Transaction t WHERE t.webAuthAccount = :account"
          + " AND t.webAuthAccountMemo = :accountMemo"
          + " AND t.requestAssetCode = :assetCode"
          + " AND (:kind IS NULL OR t.kind = :kind)"
          + " AND t.startedAt > :noOlderThan"
          + " AND t.startedAt < :olderThan"
          + " ORDER BY t.startedAt DESC")
  List<JdbcSep24Transaction> findTransactionsWithMemoAndFilters(
      @Param("account") String account,
      @Param("accountMemo") String accountMemo,
      @Param("assetCode") String assetCode,
      @Param("kind") String kind,
      @Param("noOlderThan") Instant noOlderThan,
      @Param("olderThan") Instant olderThan,
      Pageable pageable);

  Page<JdbcSep24Transaction> findByStatusIn(List<String> allowedStatuses, Pageable pageable);
}
