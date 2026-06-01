package org.stellar.anchor.platform.data;

import jakarta.transaction.Transactional;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;

public interface JdbcSep38QuoteRepo extends CrudRepository<JdbcSep38Quote, String> {
  Optional<JdbcSep38Quote> findById(@NonNull String id);

  @Modifying
  @Transactional
  @Query(
      "UPDATE JdbcSep38Quote q SET q.transactionId = :transactionId"
          + " WHERE q.id = :quoteId AND q.transactionId IS NULL")
  int bindToTransaction(
      @Param("quoteId") String quoteId, @Param("transactionId") String transactionId);
}
