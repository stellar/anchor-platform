package org.stellar.anchor.platform.data;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.stellar.anchor.client.ClientConfig.ClientType;

public interface JdbcClientConfigRepo extends CrudRepository<JdbcClientConfig, String> {

  @Override
  @EntityGraph(attributePaths = {"signingKeys", "domains", "destinationAccounts"})
  List<JdbcClientConfig> findAll();

  @Override
  @EntityGraph(attributePaths = {"signingKeys", "domains", "destinationAccounts"})
  Optional<JdbcClientConfig> findById(String name);

  @EntityGraph(attributePaths = {"signingKeys", "domains", "destinationAccounts"})
  List<JdbcClientConfig> findByType(ClientType type);

  @EntityGraph(attributePaths = {"signingKeys", "domains", "destinationAccounts"})
  @Query("SELECT c FROM JdbcClientConfig c JOIN c.domains d WHERE d = :domain")
  JdbcClientConfig findByDomain(@Param("domain") String domain);

  @EntityGraph(attributePaths = {"signingKeys", "domains", "destinationAccounts"})
  @Query("SELECT c FROM JdbcClientConfig c JOIN c.signingKeys k WHERE k = :signingKey")
  JdbcClientConfig findBySigningKey(@Param("signingKey") String signingKey);
}
