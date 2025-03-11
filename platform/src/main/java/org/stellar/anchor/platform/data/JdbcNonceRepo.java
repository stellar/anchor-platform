package org.stellar.anchor.platform.data;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

public interface JdbcNonceRepo extends CrudRepository<JdbcNonce, String> {

  @Query("SELECT n FROM JdbcNonce n WHERE n.expiresAt < CURRENT_TIMESTAMP")
  List<JdbcNonce> findExpiredNonces();
}
