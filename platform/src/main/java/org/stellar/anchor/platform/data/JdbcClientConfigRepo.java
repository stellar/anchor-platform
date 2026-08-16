package org.stellar.anchor.platform.data;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.stellar.anchor.client.ClientConfig.ClientType;

public interface JdbcClientConfigRepo extends CrudRepository<JdbcClientConfig, String> {

  List<JdbcClientConfig> findByType(ClientType type);

  @Query("SELECT c FROM JdbcClientConfig c JOIN c.domains d WHERE d = :domain")
  JdbcClientConfig findByDomain(@Param("domain") String domain);

  @Query("SELECT c FROM JdbcClientConfig c JOIN c.signingKeys k WHERE k = :signingKey")
  JdbcClientConfig findBySigningKey(@Param("signingKey") String signingKey);
}
