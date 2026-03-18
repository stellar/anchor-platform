package org.stellar.anchor.platform.data;

import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface JdbcNonceRepo extends CrudRepository<JdbcNonce, String> {

  @Query("SELECT COUNT(n) FROM JdbcNonce n WHERE n.expiresAt < CURRENT_TIMESTAMP")
  int countExpired();

  @Transactional
  @Modifying
  @Query("DELETE FROM JdbcNonce n WHERE n.expiresAt < CURRENT_TIMESTAMP")
  void deleteAllExpired();

  @Transactional
  @Modifying
  @Query(
      "UPDATE JdbcNonce n SET n.used = true WHERE n.id = :id AND n.used = false AND n.expiresAt > :now")
  int markAsUsed(@Param("id") String id, @Param("now") Instant now);
}
