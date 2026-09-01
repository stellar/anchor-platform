package org.stellar.anchor.platform.data;

import jakarta.transaction.Transactional;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;

public interface JdbcSep31CustomerIdOwnerRepo
    extends CrudRepository<JdbcSep31CustomerIdOwner, String> {
  @NonNull
  Optional<JdbcSep31CustomerIdOwner> findById(@NonNull String customerId);

  @Modifying
  @Transactional
  @Query(
      value =
          "INSERT INTO sep31_customer_id_owner (customer_id, creator_account, creator_memo) "
              + "VALUES (:customerId, :creatorAccount, :creatorMemo) "
              + "ON CONFLICT (customer_id) DO NOTHING",
      nativeQuery = true)
  int claimIfAbsent(
      @Param("customerId") String customerId,
      @Param("creatorAccount") String creatorAccount,
      @Param("creatorMemo") String creatorMemo);

  @Modifying
  @Transactional
  @Query(
      value =
          "UPDATE sep31_customer_id_owner "
              + "SET creator_account = :newAccount, creator_memo = :newMemo "
              + "WHERE customer_id = :customerId AND creator_account = :legacyAccount "
              + "AND creator_memo IS NOT DISTINCT FROM :legacyMemo",
      nativeQuery = true)
  int reassignIfCreatorAccountMatches(
      @Param("customerId") String customerId,
      @Param("legacyAccount") String legacyAccount,
      @Param("legacyMemo") String legacyMemo,
      @Param("newAccount") String newAccount,
      @Param("newMemo") String newMemo);
}
