package org.stellar.anchor.platform.data;

import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.stellar.anchor.sep6.Sep6Transaction;

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

  JdbcSep6Transaction findOneByWithdrawAnchorAccountAndFromAccountAndStatus(
      String withdrawAnchorAccount, String fromAccount, String status);

  List<Sep6Transaction> findByWebAuthAccountAndRequestAssetCodeOrderByStartedAtDesc(
      String webAuthAccount, String requestAssetCode);

  List<Sep6Transaction>
      findByWebAuthAccountAndWebAuthAccountMemoAndRequestAssetCodeOrderByStartedAtDesc(
          String webAuthAccount, String webAuthAccountMemo, String requestAssetCode);
}
