package org.stellar.anchor.platform.data;

import jakarta.transaction.Transactional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface JdbcNonceRepo extends CrudRepository<JdbcNonce, String> {

  @Transactional
  @Modifying
  @Query(
      value =
          "INSERT INTO nonce (id, used, expires_at) VALUES (:id, :used, :expiresAt) "
              + "ON CONFLICT (id) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") String id, @Param("used") boolean used, @Param("expiresAt") Instant expiresAt);

  /**
   * Deletes the rows whose {@code expires_at} is strictly before {@code now} and returns how many
   * were removed. {@code expires_at} is {@code TIMESTAMP WITHOUT TIME ZONE} holding UTC wall-clock
   * time, so the instant is bound rather than read from the database: the database's own clock
   * follows the JVM's default zone, not UTC.
   */
  @Transactional
  @Modifying
  @Query("DELETE FROM JdbcNonce n WHERE n.expiresAt < :now")
  int deleteExpired(@Param("now") Instant now);

  @Transactional
  @Modifying
  @Query(
      "UPDATE JdbcNonce n SET n.used = true WHERE n.id = :id AND n.used = false AND n.expiresAt > :now")
  int markAsUsed(@Param("id") String id, @Param("now") Instant now);
}
