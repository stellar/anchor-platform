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

  List<Sep6Transaction> findBySep10AccountAndRequestAssetCodeOrderByStartedAtDesc(
      String sep10Account, String requestAssetCode);

  List<Sep6Transaction>
      findBySep10AccountAndSep10AccountMemoAndRequestAssetCodeOrderByStartedAtDesc(
          String sep10Account, String sep10AccountMemo, String requestAssetCode);
}
