package org.stellar.anchor.platform.data;

import java.util.List;
import java.util.Set;
import org.springframework.data.repository.CrudRepository;

public interface JdbcCustodyTransactionRepo extends CrudRepository<JdbcCustodyTransaction, String> {

  JdbcCustodyTransaction findByExternalTxId(String externalTxId);

  JdbcCustodyTransaction findFirstBySepTxIdAndTypeOrderByCreatedAtAsc(String txnId, String type);

  JdbcCustodyTransaction findFirstByToAccountAndMemoOrderByCreatedAtDesc(
      String toAccount, String memo);

  List<JdbcCustodyTransaction> findAllByStatusAndExternalTxIdNotNull(String status);

  List<JdbcCustodyTransaction> findAllByStatusAndKindIn(String status, Set<String> kinds);
}
