package org.stellar.anchor.platform.data;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
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

  JdbcSep24Transaction findOneByToAccountAndMemoAndStatus(
      String toAccount, String memo, String status);

  JdbcSep24Transaction findOneByToAccountAndFromAccountAndStatus(
      String toAccount, String fromAccount, String status);

  List<Sep24Transaction> findBySep10AccountAndRequestAssetCodeOrderByStartedAtDesc(
      String stellarAccount, String assetCode);

  Page<JdbcSep24Transaction> findByStatusIn(List<String> allowedStatuses, Pageable pageable);
}
